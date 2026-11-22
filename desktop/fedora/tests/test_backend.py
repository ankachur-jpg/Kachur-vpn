"""Pure-Python backend tests; no GTK, root or VPN server required."""
import sys
from pathlib import Path
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from kachur_fedora.backend import (
    VPNError, check_interface, parse_handshakes, parse_transfer,
    pretty_bytes, read_state, switch_tunnel,
)


class BackendTests(unittest.TestCase):
    def test_interface_name(self):
        self.assertEqual(check_interface(" kch0 "), "kch0")
        self.assertEqual(check_interface(" amn0 "), "amn0")
        for value in ("", "../x", "a/b", "x" * 16, "-bad", "hi;id", "abc def"):
            with self.subTest(value=value):
                with self.assertRaises(VPNError):
                    check_interface(value)

    def test_transfer_and_handshakes(self):
        self.assertEqual(parse_transfer("pubkey 100 200\nother 50 75"), (150, 275))
        self.assertEqual(parse_transfer("bad row\npub no 100"), (0, 0))
        self.assertEqual(parse_handshakes("key1 0\nkey2 1730000000"), 1730000000)
        self.assertEqual(parse_handshakes("bad"), 0)
        self.assertEqual(pretty_bytes(1024), "1.0 КБ")

    @patch("kachur_fedora.backend.Path.is_dir", return_value=False)
    def test_inactive(self, is_dir):
        self.assertFalse(read_state("amn0").active)

    @patch("kachur_fedora.backend._tool")
    @patch("kachur_fedora.backend._run")
    @patch("kachur_fedora.backend.Path.read_text", side_effect=["123", "456"])
    @patch("kachur_fedora.backend.Path.is_dir", return_value=True)
    def test_periodic_state_read_never_requests_privileges(
        self, is_dir, read_text, run, tool
    ):
        state = read_state("kch0")
        self.assertTrue(state.active)
        self.assertEqual((state.received, state.sent), (123, 456))
        self.assertEqual(state.last_handshake, 0)
        tool.assert_not_called()
        run.assert_not_called()

    @patch(
        "kachur_fedora.backend._tool",
        side_effect=lambda name: {
            "awg": "/usr/local/bin/awg",
            "pkexec": "/usr/bin/pkexec",
        }[name],
    )
    @patch("kachur_fedora.backend._run")
    @patch("kachur_fedora.backend.Path.read_text", side_effect=["123", "456"])
    @patch("kachur_fedora.backend.Path.is_dir", return_value=True)
    def test_explicit_handshake_check_uses_pkexec(
        self, is_dir, read_text, run, tool
    ):
        run.return_value = Mock(returncode=0, stdout="peer 1730000000\n")
        state = read_state("kch0", check_handshake=True)
        self.assertEqual(state.last_handshake, 1730000000)
        self.assertEqual((state.received, state.sent), (123, 456))
        self.assertEqual(
            run.call_args.args[0],
            ["/usr/bin/pkexec", "/usr/local/bin/awg", "show", "kch0", "latest-handshakes"],
        )

    @patch(
        "kachur_fedora.backend._tool",
        side_effect=lambda name: {
            "awg": "/usr/local/bin/awg",
            "pkexec": "/usr/bin/pkexec",
        }[name],
    )
    @patch("kachur_fedora.backend._run")
    @patch("kachur_fedora.backend.Path.read_text", side_effect=["123", "456"])
    @patch("kachur_fedora.backend.Path.is_dir", return_value=True)
    def test_cancelled_handshake_check_is_reported(
        self, is_dir, read_text, run, tool
    ):
        run.return_value = Mock(returncode=126, stdout="", stderr="")
        with self.assertRaisesRegex(VPNError, "запрос прав администратора"):
            read_state("kch0", check_handshake=True)

    @patch("kachur_fedora.backend._tool", side_effect=lambda s: "/usr/bin/" + s)
    @patch("kachur_fedora.backend._run")
    def test_does_not_start_without_user_click(self, run, tool):
        run.return_value = Mock(returncode=0)
        switch_tunnel("kch0", True)
        self.assertEqual(run.call_args.args[0], [
            "/usr/bin/pkexec", "/usr/bin/env",
            "WG_QUICK_USERSPACE_IMPLEMENTATION=/usr/bin/amneziawg-go",
            "/usr/bin/awg-quick", "up", "kch0"
        ])

    @patch("kachur_fedora.backend._tool")
    @patch("kachur_fedora.backend._run")
    def test_existing_amnezia_interface_is_never_managed(self, run, tool):
        with self.assertRaises(VPNError):
            switch_tunnel("amn0", False)
        run.assert_not_called()
        tool.assert_not_called()

    def test_shell_injection_blocked(self):
        with self.assertRaises(VPNError):
            switch_tunnel("x; rm -rf /", True)


if __name__ == "__main__":
    unittest.main()
