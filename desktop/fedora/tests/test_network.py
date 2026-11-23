"""Regression tests for Fedora network setup. No host networks are modified."""
from importlib.machinery import SourceFileLoader
from importlib.util import module_from_spec, spec_from_loader
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_profile_import import VALID

SCRIPT = Path(__file__).resolve().parents[1] / "kachur-vpn-network.py"
LOADER = SourceFileLoader("kachur_vpn_network", str(SCRIPT))
SPEC = spec_from_loader(LOADER.name, LOADER)
network = module_from_spec(SPEC)
LOADER.exec_module(network)


class NetworkTests(unittest.TestCase):
    def test_legacy_table_off_uses_service_routes_and_default_dns(self):
        profile = VALID.decode().replace("DNS = 1.1.1.1, 1.0.0.1", "Table = off")
        profile = profile.replace("PersistentKeepalive = 25", "RekeyAfterTime = 100-120")
        config, dns, ipv6 = network.prepare_profile(profile)
        self.assertEqual(config.count("Table = off"), 1)
        self.assertIn("RekeyAfterTime = 100-120", config)
        self.assertEqual(dns, ["1.1.1.1", "1.0.0.1"])
        self.assertFalse(ipv6)

    def test_profile_hooks_blocked_case_insensitively(self):
        profile = VALID.decode().replace("[Interface]", "[Interface]\nPrEUp = id")
        with self.assertRaisesRegex(network.NetworkError, "команды"):
            network.prepare_profile(profile)

    def test_connected_amnezia_stops_before_firewall_query(self):
        with patch.object(network, "device", return_value=True), patch.object(network, "required") as run:
            with self.assertRaisesRegex(network.NetworkError, "Отключите AmneziaVPN"):
                network.check_amnezia()
            run.assert_not_called()

    def test_stale_ipv6_anchor_stops_without_modifying_firewall(self):
        outputs = ["-P OUTPUT ACCEPT", "-A OUTPUT -j amnvpn.anchors"]
        with patch.object(network, "device", return_value=False), patch.object(
            network, "required", side_effect=outputs,
        ) as run:
            with self.assertRaisesRegex(network.NetworkError, "KillSwitch"):
                network.check_amnezia()
            self.assertEqual([call.args[:5] for call in run.call_args_list], [
                ("iptables", "-w", "2", "-S", "OUTPUT"),
                ("ip6tables", "-w", "2", "-S", "OUTPUT"),
            ])

    def test_offline_test_has_systemd_deadline_and_independent_cleanup(self):
        with patch.object(network, "device", return_value=False), patch.object(
            network, "check_amnezia",
        ), patch.object(network, "run", return_value=SimpleNamespace(returncode=0)) as run, patch.object(
            network, "required", return_value="Откат Kachur: OK.\n",
        ):
            self.assertEqual(network.start("offline-test", 1000), 0)
            args = run.call_args.args
            self.assertIn("--property=RuntimeMaxSec=75s", args)
            self.assertIn("--wait", args)
            self.assertIn(f"--property=ExecStopPost={SCRIPT} cleanup", args)

    def test_cleanup_removes_policy_even_when_tun_disappeared(self):
        with TemporaryDirectory() as directory:
            marker = Path(directory) / "owned"
            marker.write_text("-4\n-6")
            with patch.object(network, "OWNED", marker), patch.object(
                network, "device", return_value=False,
            ), patch.object(network, "required", return_value=""), patch.object(network, "run", return_value=SimpleNamespace(stdout="")) as run:
                network.cleanup()
                self.assertEqual([call.args for call in run.call_args_list], [
                    ("ip", "-4", "rule", "delete", *network.RULE),
                    ("ip", "-4", "route", "flush", "table", network.TABLE, "proto", network.PROTOCOL),
                    ("ip", "-6", "rule", "delete", *network.RULE),
                    ("ip", "-6", "route", "flush", "table", network.TABLE, "proto", network.PROTOCOL),
                    ("ip", "-4", "route", "show", "table", network.TABLE),
                    ("ip", "-6", "route", "show", "table", network.TABLE),
                ])

    def test_cleanup_leaves_network_untouched_when_setup_never_owned_it(self):
        with TemporaryDirectory() as directory:
            with patch.object(network, "OWNED", Path(directory) / "missing"), patch.object(
                network, "run",
            ) as run:
                network.cleanup()
                run.assert_not_called()

    def test_probe_refuses_to_try_https_on_wrong_route(self):
        with patch.object(network, "check_amnezia"), patch.object(network, "device", return_value=True), patch.object(
            network, "route_device", return_value="wlp2s0",
        ), patch.object(network, "required") as run:
            with self.assertRaisesRegex(network.NetworkError, "не проходит через kch0"):
                network.probe(1000)
            run.assert_not_called()
