"""
Eye Lab on AWS:

    browser --HTTPS/WSS--> CloudFront --HTTP + secret header--> ALB --> ECS Fargate (Graviton, arm64)
                                                                          |-> Amazon Bedrock (Claude)
                                                                          |-> Amazon Polly
                                                                          '-> CloudWatch Logs (EMF metrics)

CloudFront gives the page HTTPS, which browsers require before they allow the webcam, and carries the WebSocket.
The ALB answers 403 to anything that did not come through CloudFront.
"""

from __future__ import annotations

import pathlib

from aws_cdk import (CfnOutput, Duration, Fn, RemovalPolicy, Stack, aws_cloudfront as cloudfront,
                     aws_cloudfront_origins as origins, aws_cloudwatch as cw, aws_ec2 as ec2, aws_ecs as ecs,
                     aws_ecs_patterns as ecs_patterns, aws_elasticloadbalancingv2 as elbv2, aws_iam as iam,
                     aws_logs as logs)
from aws_cdk.aws_ecr_assets import Platform
from aws_cdk import IgnoreMode
from constructs import Construct

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
NAMESPACE = "Vakyam/EyeLab"
ORIGIN_HEADER = "X-Eyelab-Origin"


class EyeLabStack(Stack):
    def __init__(self, scope: Construct, construct_id: str, *, bedrock_model: str, max_sessions: int,
                 polly_voice: str, **kwargs) -> None:
        super().__init__(scope, construct_id, **kwargs)

        # Public subnets only: the task pulls its image and calls AWS APIs directly, so there is no NAT bill.
        vpc = ec2.Vpc(self, "Vpc", max_azs=2, nat_gateways=0, subnet_configuration=[
            ec2.SubnetConfiguration(name="public", subnet_type=ec2.SubnetType.PUBLIC, cidr_mask=24)])
        cluster = ecs.Cluster(self, "Cluster", vpc=vpc, container_insights_v2=ecs.ContainerInsights.ENABLED)

        image = ecs.ContainerImage.from_asset(
            str(REPO_ROOT), file="eyelab/Dockerfile", platform=Platform.LINUX_ARM64, ignore_mode=IgnoreMode.DOCKER,
            exclude=["**", "!eyelab/requirements.txt", "!eyelab/eyecore/**", "!eyelab/server/**", "!eyelab/Dockerfile",
                     "!app/src/main/assets/face_detection_yunet_2023mar.onnx", "**/__pycache__"])
        log_group = logs.LogGroup(self, "Logs", retention=logs.RetentionDays.ONE_MONTH,
                                  removal_policy=RemovalPolicy.DESTROY)

        service = ecs_patterns.ApplicationLoadBalancedFargateService(
            self, "Service", cluster=cluster, cpu=1024, memory_limit_mib=2048, desired_count=1,
            public_load_balancer=True, assign_public_ip=True,
            task_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PUBLIC),
            runtime_platform=ecs.RuntimePlatform(cpu_architecture=ecs.CpuArchitecture.ARM64,
                                                 operating_system_family=ecs.OperatingSystemFamily.LINUX),
            circuit_breaker=ecs.DeploymentCircuitBreaker(rollback=True),
            min_healthy_percent=100,  # a deploy starts the new task before stopping the old one: no dropped sessions
            task_image_options=ecs_patterns.ApplicationLoadBalancedTaskImageOptions(
                image=image, container_port=8080,
                log_driver=ecs.LogDrivers.aws_logs(stream_prefix="eyelab", log_group=log_group),
                environment={
                    "EYELAB_BEDROCK_REGION": self.region,
                    "EYELAB_BEDROCK_MODEL": bedrock_model,
                    "EYELAB_POLLY": "1",
                    "EYELAB_POLLY_VOICE": polly_voice,
                    "EYELAB_MAX_SESSIONS": str(max_sessions),
                }),
        )
        service.target_group.configure_health_check(path="/healthz", healthy_http_codes="200",
                                                    interval=Duration.seconds(15))
        service.target_group.set_attribute("deregistration_delay.timeout_seconds", "30")
        service.load_balancer.set_attribute("idle_timeout.timeout_seconds", "300")  # long-lived WebSockets

        # Only CloudFront may reach the ALB: it adds a header whose value is this stack's own UUID
        token = Fn.select(2, Fn.split("/", self.stack_id))
        service.listener.add_action("FromCloudFront", priority=1,
                                    conditions=[elbv2.ListenerCondition.http_header(ORIGIN_HEADER, [token])],
                                    action=elbv2.ListenerAction.forward([service.target_group]))
        service.listener.node.default_child.add_property_override("DefaultActions", [{
            "Type": "fixed-response",
            "FixedResponseConfig": {"StatusCode": "403", "ContentType": "text/plain", "MessageBody": "Forbidden"},
        }])

        task_role = service.task_definition.task_role
        task_role.add_to_principal_policy(iam.PolicyStatement(
            sid="ClaudeOnBedrock", actions=["bedrock-mantle:CreateInference"], resources=["*"]))
        task_role.add_to_principal_policy(iam.PolicyStatement(
            sid="Speech", actions=["polly:SynthesizeSpeech"], resources=["*"]))

        scaling = service.service.auto_scale_task_count(min_capacity=1, max_capacity=4)
        scaling.scale_on_cpu_utilization("Cpu", target_utilization_percent=60,
                                         scale_in_cooldown=Duration.minutes(5), scale_out_cooldown=Duration.minutes(1))

        distribution = cloudfront.Distribution(
            self, "Cdn", comment="Vakyam Eye Lab",
            default_behavior=cloudfront.BehaviorOptions(
                origin=origins.LoadBalancerV2Origin(
                    service.load_balancer, protocol_policy=cloudfront.OriginProtocolPolicy.HTTP_ONLY,
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
            cw.GraphWidget(title="Calibration (s)", left=[metric("CalibrationSeconds")]),
            cw.GraphWidget(title="Service CPU / memory (%)",
                           left=[service.service.metric_cpu_utilization(), service.service.metric_memory_utilization()]),
        )
        cw.Alarm(self, "SlowFrames", metric=metric("FrameMsP95", "Maximum"), threshold=150, evaluation_periods=2,
                 alarm_description="Eye Lab frames take over 150 ms at p95: users will feel lag",
                 treat_missing_data=cw.TreatMissingData.NOT_BREACHING)
        cw.Alarm(self, "SentenceFallbacks", metric=metric("SentenceFallback", "Sum"), threshold=5, evaluation_periods=1,
                 alarm_description="Claude on Bedrock failed five times in five minutes; users get built-in sentences",
                 treat_missing_data=cw.TreatMissingData.NOT_BREACHING)

        CfnOutput(self, "EyeLabUrl", value=f"https://{distribution.distribution_domain_name}")
        CfnOutput(self, "DashboardName", value=dashboard.dashboard_name)
        CfnOutput(self, "LogGroupName", value=log_group.log_group_name)
