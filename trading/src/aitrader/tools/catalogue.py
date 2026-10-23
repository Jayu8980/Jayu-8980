"""Imports every tool module so their @tool registrations run exactly once."""

from aitrader.tools import (  # noqa: F401
    costs,
    derivatives,
    flows,
    fundamentals,
    microstructure,
    portfolio,
    risk,
    sizing,
    stats,
    technical,
    validation,
)
