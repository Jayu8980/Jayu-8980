from pathlib import Path
from typing import Any

import pytest
import yaml
from pydantic import ValidationError

from aitrader.config import RiskLimits, Stage, SystemConfig, load_risk_limits, load_system_config, secret
from aitrader.config.settings import CONFIG_DIR


def _raw(name: str) -> dict[str, Any]:
    data = yaml.safe_load((CONFIG_DIR / name).read_text())
    assert isinstance(data, dict)
    return data


def test_shipped_configs_load() -> None:
    system = load_system_config()
    limits = load_risk_limits()
    assert system.stage is Stage.BACKTEST
    assert limits.per_trade.max_risk_fraction == 0.01
    assert limits.drawdown.daily_limit == 0.03
    assert limits.drawdown.total_limit == 0.10


def test_unpinned_llm_models_are_refused_before_llm_use() -> None:
    llm = load_system_config().llm
    with pytest.raises(ValueError, match="pinned model id"):
        llm.require_pinned()
    for alias in ("", "some-model-latest"):
        bad = llm.model_copy(update={"models": llm.models.model_copy(update={"reasoning": alias})})
        with pytest.raises(ValueError, match="pinned model id"):
            bad.require_pinned()
    assert not llm.pinned
    priced = llm.model_copy(
        update={
            "models": llm.models.model_copy(update={"reasoning": "model-a", "extraction": "model-b"}),
            "pricing_usd_per_mtok": {"model-a": {"input": 1.0, "output": 2.0}},
        }
    )
    with pytest.raises(ValueError, match="no entry for 'model-b'"):
        priced.require_pinned()


def test_paper_and_live_need_durable_backends() -> None:
    raw = _raw("system.yaml")
    raw["stage"] = "paper"
    with pytest.raises(ValidationError, match="durable"):
        SystemConfig.model_validate(raw)


def test_daily_drawdown_must_be_below_total() -> None:
    raw = _raw("limits.yaml")
    raw["drawdown"]["daily_limit"] = 0.10
    with pytest.raises(ValidationError, match="daily drawdown"):
        RiskLimits.model_validate(raw)


def test_per_trade_risk_is_hard_capped() -> None:
    raw = _raw("limits.yaml")
    raw["per_trade"]["max_risk_fraction"] = 0.05
    with pytest.raises(ValidationError):
        RiskLimits.model_validate(raw)


def test_every_setup_needs_a_min_rr() -> None:
    raw = _raw("limits.yaml")
    del raw["risk_reward"]["min_rr_after_costs"]["breakout"]
    with pytest.raises(ValidationError, match="min R:R missing"):
        RiskLimits.model_validate(raw)


def test_order_rate_cap_cannot_be_raised_beyond_ten() -> None:
    raw = _raw("system.yaml")
    raw["broker"]["max_orders_per_second"] = 20
    with pytest.raises(ValidationError):
        SystemConfig.model_validate(raw)


def test_secrets_come_from_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AITRADER_TEST_SECRET", raising=False)
    with pytest.raises(KeyError):
        secret("AITRADER_TEST_SECRET")
    monkeypatch.setenv("AITRADER_TEST_SECRET", "s3cret")
    assert secret("AITRADER_TEST_SECRET") == "s3cret"


def test_config_files_contain_no_secret_values() -> None:
    for path in Path(CONFIG_DIR).glob("*.yaml"):
        text = path.read_text().lower()
        for marker in ("api_key:", "secret:", "password:", "token:"):
            assert marker not in text, f"{path.name} looks like it holds a secret ({marker})"
