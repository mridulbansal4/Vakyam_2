#!/usr/bin/env python3
"""cdk deploy [-c compute=cool -c cool_ami=ami-...] [-c compute=ec2 -c domain=... -c eip_allocation=eipalloc-...]
[-c budget_email=...] [-c gemini_model=...] [-c gemini_secret=...] (all context values are optional)."""

import os

import aws_cdk as cdk

from eyelab_stack import EyeLabStack

app = cdk.App()
EyeLabStack(
    app, "VakyamEyeLab",
    gemini_model=app.node.try_get_context("gemini_model") or "gemini-3.8-flash",
    gemini_secret=app.node.try_get_context("gemini_secret") or "vakyam/gemini-api-key",
    max_sessions=int(app.node.try_get_context("max_sessions") or 20),
    polly_voice=app.node.try_get_context("polly_voice") or "Joanna",
    compute=app.node.try_get_context("compute") or "fargate",
    cool_ami=app.node.try_get_context("cool_ami"),
    cool_instance_type=app.node.try_get_context("cool_instance_type") or "c8g.xlarge",
    task_cpu=int(app.node.try_get_context("task_cpu") or 512),
    task_memory=int(app.node.try_get_context("task_memory") or 1024),
    max_tasks=int(app.node.try_get_context("max_tasks") or 1),
    budget_usd=int(app.node.try_get_context("budget_usd") or 30),
    budget_email=app.node.try_get_context("budget_email"),
    domain=app.node.try_get_context("domain"),
    eip_allocation=app.node.try_get_context("eip_allocation"),
    ec2_instance_type=app.node.try_get_context("ec2_instance_type") or "t4g.small",
    env=cdk.Environment(account=os.environ.get("CDK_DEFAULT_ACCOUNT"),
                        region=os.environ.get("CDK_DEFAULT_REGION", "us-east-1")),
    description="Vakyam Eye Lab: OpenCV 5 eye pipeline on ECS Fargate (Graviton), Gemini, Polly",
)
cdk.Tags.of(app).add("project", "vakyam")
app.synth()
