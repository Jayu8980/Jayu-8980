"""Offline evaluation of analyst agents on historical data.

Agents are replayed point in time (each signal sees only data available at its decision
time); outcomes are then measured on the data that followed. This package is the only place
that reads forward, and it never feeds anything back into a decision except through the
attribution scores that the boss agent consumes later.
"""
