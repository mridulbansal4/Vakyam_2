#!/usr/bin/env python3
"""cdk deploy [-c compute=cool -c cool_ami=ami-...] [-c gemini_model=...] [-c gemini_secret=...] (all context values are optional)."""

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
    env=cdk.Environment(account=os.environ.get("CDK_DEFAULT_ACCOUNT"),
                        region=os.environ.get("CDK_DEFAULT_REGION", "us-east-1")),
    description="Vakyam Eye Lab: OpenCV 5 eye pipeline on ECS Fargate (Graviton), Gemini, Polly",
)
cdk.Tags.of(app).add("project", "vakyam")
app.synth()
