#!/usr/bin/env python3
"""cdk deploy -c bedrock_model=anthropic.claude-opus-5-5 (all context values are optional)."""

import os

import aws_cdk as cdk

from eyelab_stack import EyeLabStack

app = cdk.App()
EyeLabStack(
    app, "VakyamEyeLab",
    bedrock_model=app.node.try_get_context("bedrock_model") or "anthropic.claude-opus-5-5",
    max_sessions=int(app.node.try_get_context("max_sessions") or 20),
    polly_voice=app.node.try_get_context("polly_voice") or "Joanna",
    env=cdk.Environment(account=os.environ.get("CDK_DEFAULT_ACCOUNT"),
                        region=os.environ.get("CDK_DEFAULT_REGION", "us-east-1")),
    description="Vakyam Eye Lab: OpenCV 5 eye pipeline on ECS Fargate (Graviton), Claude on Bedrock, Polly",
)
cdk.Tags.of(app).add("project", "vakyam")
app.synth()
