"""
Libraries that download through huggingface_hub. Each runs in a subprocess, because they read
HF_ENDPOINT when they are imported. Skipped when the library is not installed.
"""

import importlib.util
import os
import subprocess
import sys

import pytest


def run(repository, tmp_path, code):
    env = dict(os.environ, HF_ENDPOINT=repository.url, HF_HOME=str(tmp_path / "hf"), HF_HUB_DISABLE_TELEMETRY="1",
               HF_HUB_DISABLE_PROGRESS_BARS="1", HF_TOKEN="")
    result = subprocess.run([sys.executable, "-I", "-c", code], env=env, capture_output=True, text=True, timeout=600)
    assert result.returncode == 0, result.stderr[-3000:]
    return result.stdout.strip()


@pytest.mark.skipif(importlib.util.find_spec("transformers") is None, reason="transformers not installed")
def test_transformers_config_and_tokenizer(repository, tmp_path):
    out = run(repository, tmp_path, """
from transformers import AutoConfig, AutoTokenizer
config = AutoConfig.from_pretrained("hf-internal-testing/tiny-random-gpt2")
tokenizer = AutoTokenizer.from_pretrained("hf-internal-testing/tiny-random-gpt2")
print(config.model_type, len(tokenizer("hello world")["input_ids"]))
""")
    assert out.splitlines()[-1].startswith("gpt2 ")


@pytest.mark.skipif(importlib.util.find_spec("datasets") is None, reason="datasets not installed")
def test_datasets_load_dataset(repository, tmp_path):
    out = run(repository, tmp_path, """
import datasets
ds = datasets.load_dataset("julien-c/titanic-survival", split="train")
print(ds.num_rows)
""")
    assert int(out.splitlines()[-1]) > 800
