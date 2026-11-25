"""Validation tests for the root-owned profile import helper."""
import base64
import importlib.util
import json
from importlib.machinery import SourceFileLoader
from pathlib import Path
import unittest

HELPER = Path(__file__).resolve().parents[1] / "kachur-vpn-helper"
LOADER = SourceFileLoader("kachur_vpn_helper", str(HELPER))
SPEC = importlib.util.spec_from_loader("kachur_vpn_helper", LOADER)
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)

KEY = base64.b64encode(bytes(range(32))).decode("ascii")
VALID = f"""[Interface]
PrivateKey = {KEY}
Address = 10.8.1.11/32
DNS = 1.1.1.1, 1.0.0.1
Jc = 6
Jmin = 10
Jmax = 50
S1 = 12
S2 = 12

[Peer]
PublicKey = {KEY}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = vpn.example.com:37649
PersistentKeepalive = 25
""".encode()


class ProfileImportTests(unittest.TestCase):

    def test_bypass_json_accepts_ips_and_cidrs_and_deduplicates(self):
        data = b'{"version":1,"bypass_vpn":["1.1.1.1","203.0.113.0/24","1.1.1.1/32"]}'
        normalized = json.loads(helper.validate_bypass(data))
        self.assertEqual(
            normalized,
            {"version": 1, "bypass_vpn": ["1.1.1.1/32", "203.0.113.0/24"]},
        )

    def test_bypass_json_rejects_default_route(self):
        data = b'{"version":1,"bypass_vpn":["0.0.0.0/0"]}'
        with self.assertRaisesRegex(helper.ProfileError, "Маршрут по умолчанию"):
            helper.validate_bypass(data)

    def test_bypass_json_rejects_loopback(self):
        data = b'{"version":1,"bypass_vpn":["127.0.0.0/8"]}'
        with self.assertRaisesRegex(helper.ProfileError, "loopback"):
            helper.validate_bypass(data)

    def test_bypass_json_rejects_invalid_address(self):
        data = b'{"version":1,"bypass_vpn":["not-an-ip"]}'
        with self.assertRaisesRegex(helper.ProfileError, "Некорректный IP"):
            helper.validate_bypass(data)

    def test_bypass_json_rejects_unknown_fields(self):
        data = b'{"version":1,"bypass_vpn":[],"command":"id"}'
        with self.assertRaisesRegex(helper.ProfileError, "Ожидаются поля"):
            helper.validate_bypass(data)

    def test_valid_amneziawg_profile_is_accepted(self):
        normalized = helper.validate_profile(VALID)
        self.assertIn("[Interface]", normalized)
        self.assertIn("Endpoint = vpn.example.com:37649", normalized)

    def test_shell_hooks_are_rejected(self):
        unsafe = VALID.replace(
            b"Address = 10.8.1.11/32",
            b"Address = 10.8.1.11/32\nPostUp = touch /tmp/pwned",
        )
        with self.assertRaisesRegex(helper.ProfileError, "PostUp"):
            helper.validate_profile(unsafe)

    def test_unknown_directives_are_rejected(self):
        unsafe = VALID.replace(b"DNS = 1.1.1.1, 1.0.0.1\n", b"Table = off\n")
        with self.assertRaises(helper.ProfileError):
            helper.validate_profile(unsafe)

    def test_bad_key_is_rejected(self):
        unsafe = VALID.replace(KEY.encode(), b"not-a-key", 1)
        with self.assertRaisesRegex(helper.ProfileError, "ключ"):
            helper.validate_profile(unsafe)

    def test_bad_endpoint_port_is_rejected(self):
        unsafe = VALID.replace(b"vpn.example.com:37649", b"vpn.example.com:70000")
        with self.assertRaisesRegex(helper.ProfileError, "Endpoint"):
            helper.validate_profile(unsafe)

    def test_invalid_allowed_ip_is_rejected(self):
        unsafe = VALID.replace(b"0.0.0.0/0, ::/0", b"not-an-ip")
        with self.assertRaisesRegex(helper.ProfileError, "AllowedIPs"):
            helper.validate_profile(unsafe)

    def test_duplicate_keys_are_rejected(self):
        unsafe = VALID.replace(
            b"Address = 10.8.1.11/32",
            b"Address = 10.8.1.11/32\nAddress = 10.8.1.12/32",
        )
        with self.assertRaisesRegex(helper.ProfileError, "повторно"):
            helper.validate_profile(unsafe)

    def test_missing_peer_is_rejected(self):
        unsafe = VALID.split(b"[Peer]")[0]
        with self.assertRaises(helper.ProfileError):
            helper.validate_profile(unsafe)


if __name__ == "__main__":
    unittest.main()
