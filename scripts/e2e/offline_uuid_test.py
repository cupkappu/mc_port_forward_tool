#!/usr/bin/env python3
from offline_uuid import offline_uuid


def test_known_offline_uuid():
    assert str(offline_uuid("E2EPlayer")) == "7ae59273-5a35-3bf0-8aa6-4d7259319819"


if __name__ == "__main__":
    test_known_offline_uuid()

