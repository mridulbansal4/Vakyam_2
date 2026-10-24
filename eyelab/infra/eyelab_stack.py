"""
Eye Lab on AWS, with two compute options behind the same front door:

    browser --HTTPS/WSS--> CloudFront --HTTP + secret header--> ALB --> compute
                                                                          |-> Gemini API (key in Secrets Manager)
                                                                          |-> Amazon Polly
                                                                          '-> CloudWatch Logs

    compute=fargate (default)  ECS Fargate on Graviton (arm64), stock OpenCV 5 from PyPI in a container
    compute=cool               EC2 Graviton4 Auto Scaling group from the "Cloud Optimized OpenCV for AWS Graviton4"
                               AMI: the service runs in COOL's own Python environment, so COOL executes every frame

CloudFront gives the page HTTPS, which browsers require before they allow the webcam, and carries the WebSocket.
The ALB answers 403 to anything that did not come through CloudFront.
"""

from __future__ import annotations

import pathlib

from aws_cdk import (CfnOutput, Duration, Fn, IgnoreMode, RemovalPolicy, Stack, aws_autoscaling as autoscaling,
                     aws_cloudfront as cloudfront, aws_cloudfront_origins as origins, aws_cloudwatch as cw,
                     aws_ec2 as ec2, aws_ecs as ecs, aws_ecs_patterns as ecs_patterns,
                     aws_elasticloadbalancingv2 as elbv2, aws_iam as iam, aws_logs as logs, aws_s3_assets as s3_assets,
                     aws_secretsmanager as secretsmanager)
from aws_cdk.aws_ecr_assets import Platform
from constructs import Construct

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
NAMESPACE = "Vakyam/EyeLab"
ORIGIN_HEADER = "X-Eyelab-Origin"
COOL_VENV = "/opt/cool/venvs/python_3.12"
# What the service needs from the repository, for the container image and the COOL code bundle alike
SERVICE_FILES = ["**", "!eyelab/requirements.txt", "!eyelab/requirements-cool.txt", "!eyelab/eyecore/**",
                 "!eyelab/server/**", "!eyelab/coach/**", "!eyelab/Dockerfile",
                 "!app/src/main/assets/face_detection_yunet_2023mar.onnx", "!app/src/main/assets/EnglishWords.txt",
                 "!app/src/main/assets/EnglishBigrams.txt", "**/__pycache__"]


class EyeLabStack(Stack):
    def __init__(self, scope: Construct, construct_id: str, *, gemini_model: str, gemini_secret: str, max_sessions: int,
                 polly_voice: str, compute: str = "fargate", cool_ami: str | None = None,
                 cool_instance_type: str = "c8g.xlarge", **kwargs) -> None:
        super().__init__(scope, construct_id, **kwargs)
        if compute not in ("fargate", "cool"):
            raise ValueError("compute must be 'fargate' or 'cool'")
        if compute == "cool" and not cool_ami:
            raise ValueError("compute=cool needs -c cool_ami=ami-... (the COOL Graviton4 AMI after subscribing)")

        # Public subnets only: compute reaches AWS APIs directly, so there is no NAT bill.
        vpc = ec2.Vpc(self, "Vpc", max_azs=2, nat_gateways=0, subnet_configuration=[
            ec2.SubnetConfiguration(name="public", subnet_type=ec2.SubnetType.PUBLIC, cidr_mask=24)])
        log_group = logs.LogGroup(self, "Logs", retention=logs.RetentionDays.ONE_MONTH,
                                  removal_policy=RemovalPolicy.DESTROY)
        environment = {
            "AWS_REGION": self.region,
            "EYELAB_GEMINI_MODEL": gemini_model,
            "EYELAB_GEMINI_SECRET": gemini_secret,  # only the name: the service reads the key at run time
            "EYELAB_POLLY": "1",
            "EYELAB_POLLY_VOICE": polly_voice,
            "EYELAB_MAX_SESSIONS": str(max_sessions),
        }
        token = Fn.select(2, Fn.split("/", self.stack_id))  # CloudFront sends it; the ALB requires it

        if compute == "fargate":
            alb, compute_metrics, role = self._fargate(vpc, log_group, environment, token)
        else:
            alb, compute_metrics, role = self._cool(vpc, log_group, environment, token, cool_ami, cool_instance_type)

        # Created once by hand (see the README), so the key never passes through CDK or CloudFormation
        secretsmanager.Secret.from_secret_name_v2(self, "GeminiKey", gemini_secret).grant_read(role)
        role.add_to_principal_policy(iam.PolicyStatement(
            sid="Speech", actions=["polly:SynthesizeSpeech"], resources=["*"]))

        distribution = cloudfront.Distribution(
            self, "Cdn", comment=f"Vakyam Eye Lab ({compute})",
            default_behavior=cloudfront.BehaviorOptions(
                origin=origins.LoadBalancerV2Origin(
                    alb, protocol_policy=cloudfront.OriginProtocolPolicy.HTTP_ONLY,
                    custom_headers={ORIGIN_HEADER: token}, read_timeout=Duration.seconds(60)),
                viewer_protocol_policy=cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
                allowed_methods=cloudfront.AllowedMethods.ALLOW_ALL,
                cache_policy=cloudfront.CachePolicy.CACHING_DISABLED,
                origin_request_policy=cloudfront.OriginRequestPolicy.ALL_VIEWER,  # forwards the WebSocket upgrade
                response_headers_policy=cloudfront.ResponseHeadersPolicy.SECURITY_HEADERS,
            ),
        )

        # Observability: the service's own EMF metrics next to the platform's
        def metric(name: str, stat: str = "Average") -> cw.Metric:
            return cw.Metric(namespace=NAMESPACE, metric_name=name, dimensions_map={"Service": "eyelab"},
                             statistic=stat, period=Duration.minutes(5))

        dashboard = cw.Dashboard(self, "Dashboard", dashboard_name=f"{construct_id}-dashboard")
        dashboard.add_widgets(
            cw.GraphWidget(title="Frame time (ms)", left=[metric("FrameMsAvg"), metric("FrameMsP95", "Maximum")]),
            cw.GraphWidget(title="Sessions and selections", left=[metric("Frames", "SampleCount"),
                                                                  metric("Selections", "Sum")]),
            cw.GraphWidget(title="Sentences", left=[metric("SentenceMs")],
                           right=[metric("SentenceFallback", "Sum"), metric("Sentences", "Sum")]),
        )
        dashboard.add_widgets(
            cw.GraphWidget(title="Face found / quality issue rate", left=[metric("FaceFoundRate"),
                                                                         metric("QualityIssueRate")]),
            cw.GraphWidget(title="Calibration Coach", left=[metric("CoachRuns", "Sum"), metric("CoachReady", "Sum"),
                                                            metric("CoachFallback", "Sum")]),
            cw.GraphWidget(title=f"Compute ({compute})", left=compute_metrics),
        )
        cw.Alarm(self, "SlowFrames", metric=metric("FrameMsP95", "Maximum"), threshold=150, evaluation_periods=2,
                 alarm_description="Eye Lab frames take over 150 ms at p95: users will feel lag",
                 treat_missing_data=cw.TreatMissingData.NOT_BREACHING)
        cw.Alarm(self, "SentenceFallbacks", metric=metric("SentenceFallback", "Sum"), threshold=5, evaluation_periods=1,
                 alarm_description="Gemini failed five times in five minutes; users get built-in sentences",
                 treat_missing_data=cw.TreatMissingData.NOT_BREACHING)

        CfnOutput(self, "EyeLabUrl", value=f"https://{distribution.distribution_domain_name}")
        CfnOutput(self, "Compute", value=compute)
        CfnOutput(self, "DashboardName", value=dashboard.dashboard_name)
        CfnOutput(self, "LogGroupName", value=log_group.log_group_name)

    # ---------- compute: Fargate (stock OpenCV 5 in a container) ----------
    def _fargate(self, vpc, log_group, environment, token):
        cluster = ecs.Cluster(self, "Cluster", vpc=vpc, container_insights_v2=ecs.ContainerInsights.ENABLED)
        image = ecs.ContainerImage.from_asset(str(REPO_ROOT), file="eyelab/Dockerfile", platform=Platform.LINUX_ARM64,
                                              ignore_mode=IgnoreMode.DOCKER, exclude=SERVICE_FILES)
        service = ecs_patterns.ApplicationLoadBalancedFargateService(
            self, "Service", cluster=cluster, cpu=1024, memory_limit_mib=2048, desired_count=1,
            public_load_balancer=True, assign_public_ip=True,
            task_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PUBLIC),
            runtime_platform=ecs.RuntimePlatform(cpu_architecture=ecs.CpuArchitecture.ARM64,
                                                 operating_system_family=ecs.OperatingSystemFamily.LINUX),
            circuit_breaker=ecs.DeploymentCircuitBreaker(rollback=True),
            min_healthy_percent=100,  # a deploy starts the new task before stopping the old one: no dropped sessions
            task_image_options=ecs_patterns.ApplicationLoadBalancedTaskImageOptions(
                image=image, container_port=8080, environment=environment,
                log_driver=ecs.LogDrivers.aws_logs(stream_prefix="eyelab", log_group=log_group)),
        )
        service.target_group.configure_health_check(path="/healthz", healthy_http_codes="200",
                                                    interval=Duration.seconds(15))
        service.target_group.set_attribute("deregistration_delay.timeout_seconds", "30")
        service.load_balancer.set_attribute("idle_timeout.timeout_seconds", "300")  # long-lived WebSockets
        service.listener.add_action("FromCloudFront", priority=1,
                                    conditions=[elbv2.ListenerCondition.http_header(ORIGIN_HEADER, [token])],
                                    action=elbv2.ListenerAction.forward([service.target_group]))
        service.listener.node.default_child.add_property_override("DefaultActions", [{
            "Type": "fixed-response",
            "FixedResponseConfig": {"StatusCode": "403", "ContentType": "text/plain", "MessageBody": "Forbidden"},
        }])
        scaling = service.service.auto_scale_task_count(min_capacity=1, max_capacity=4)
        scaling.scale_on_cpu_utilization("Cpu", target_utilization_percent=60,
                                         scale_in_cooldown=Duration.minutes(5), scale_out_cooldown=Duration.minutes(1))
        return (service.load_balancer,
                [service.service.metric_cpu_utilization(), service.service.metric_memory_utilization()],
                service.task_definition.task_role)

    # ---------- compute: EC2 Graviton4 from the COOL AMI ----------
    def _cool(self, vpc, log_group, environment, token, ami_id, instance_type):
        role = iam.Role(self, "CoolRole", assumed_by=iam.ServicePrincipal("ec2.amazonaws.com"), managed_policies=[
            iam.ManagedPolicy.from_aws_managed_policy_name("AmazonSSMManagedInstanceCore"),   # shell without SSH
            iam.ManagedPolicy.from_aws_managed_policy_name("CloudWatchAgentServerPolicy")])
        code = s3_assets.Asset(self, "CoolCode", path=str(REPO_ROOT), ignore_mode=IgnoreMode.DOCKER,
                               exclude=SERVICE_FILES)
        code.grant_read(role)
        log_group.grant_write(role)

        env_lines = "\n".join(f"Environment={k}={v}" for k, v in environment.items())
        user_data = ec2.UserData.for_linux()
        user_data.add_commands(
            "set -euxo pipefail",
            "apt-get update -y && apt-get install -y unzip",
            "snap install aws-cli --classic || true",
            "mkdir -p /srv/eyelab /var/log/eyelab",
            f"aws s3 cp {code.s3_object_url} /tmp/eyelab.zip && unzip -oq /tmp/eyelab.zip -d /srv/eyelab",
            f"{COOL_VENV}/bin/pip install -q -r /srv/eyelab/eyelab/requirements-cool.txt",
            # Serve only if the COOL build is the cv2 Python will load
            f"{COOL_VENV}/bin/python -c \"import cv2; assert '/opt/cool' in cv2.__file__, cv2.__file__; "
            "print('COOL', cv2.__version__, cv2.__file__)\" | tee /var/log/eyelab/opencv-build.txt",
            "useradd --system --home /srv/eyelab eyelab || true",
            "chown -R eyelab /srv/eyelab /var/log/eyelab",
            "cat > /etc/systemd/system/eyelab.service <<'UNIT'\n"
            "[Unit]\nDescription=Vakyam Eye Lab (COOL)\nAfter=network-online.target\n\n"
            "[Service]\nUser=eyelab\nWorkingDirectory=/srv/eyelab/eyelab\n"
            f"{env_lines}\n"
            f"ExecStart={COOL_VENV}/bin/python -m uvicorn server.app:app --host 0.0.0.0 --port 8080 "
            "--proxy-headers --forwarded-allow-ips * --ws-max-size 262144\n"
            "Restart=always\nStandardOutput=append:/var/log/eyelab/eyelab.log\n"
            "StandardError=append:/var/log/eyelab/eyelab.log\n\n"
            "[Install]\nWantedBy=multi-user.target\nUNIT",
            "systemctl daemon-reload && systemctl enable --now eyelab",
            # Ship the service log to CloudWatch Logs
            "curl -sfo /tmp/cwagent.deb https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/arm64/latest/"
            "amazon-cloudwatch-agent.deb && dpkg -i /tmp/cwagent.deb",
            "cat > /opt/aws/amazon-cloudwatch-agent/etc/eyelab.json <<CFG\n"
            '{"logs": {"logs_collected": {"files": {"collect_list": [{"file_path": "/var/log/eyelab/eyelab.log", '
            f'"log_group_name": "{log_group.log_group_name}", "log_stream_name": "{{instance_id}}"}}]}}}}}}}}\nCFG',
            "/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s "
            "-c file:/opt/aws/amazon-cloudwatch-agent/etc/eyelab.json",
        )

        asg = autoscaling.AutoScalingGroup(
            self, "CoolAsg", vpc=vpc, vpc_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PUBLIC),
            associate_public_ip_address=True, instance_type=ec2.InstanceType(instance_type),
            machine_image=ec2.MachineImage.generic_linux({self.region: ami_id}), role=role, user_data=user_data,
            require_imdsv2=True, min_capacity=1, max_capacity=4,
            health_checks=autoscaling.HealthChecks.with_additional_checks(
                additional_types=[autoscaling.AdditionalHealthCheckType.ELB], grace_period=Duration.minutes(10)))
        asg.scale_on_cpu_utilization("Cpu", target_utilization_percent=60)

        alb = elbv2.ApplicationLoadBalancer(self, "CoolAlb", vpc=vpc, internet_facing=True,
                                            idle_timeout=Duration.seconds(300))
        listener = alb.add_listener("Http", port=80, open=True, default_action=elbv2.ListenerAction.fixed_response(
            403, content_type="text/plain", message_body="Forbidden"))
        listener.add_targets(
            "Cool", port=8080, protocol=elbv2.ApplicationProtocol.HTTP, targets=[asg], priority=1,
            conditions=[elbv2.ListenerCondition.http_header(ORIGIN_HEADER, [token])],
            deregistration_delay=Duration.seconds(30),
            health_check=elbv2.HealthCheck(path="/healthz", healthy_http_codes="200", interval=Duration.seconds(15)))
        cpu = cw.Metric(namespace="AWS/EC2", metric_name="CPUUtilization",
                        dimensions_map={"AutoScalingGroupName": asg.auto_scaling_group_name},
                        statistic="Average", period=Duration.minutes(5))
        CfnOutput(self, "CoolAmi", value=ami_id)
        return alb, [cpu], role
