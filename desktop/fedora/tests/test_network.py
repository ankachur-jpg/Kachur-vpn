"""Regression tests for Fedora network setup. No host networks are modified."""
import ipaddress
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

    def test_stale_amnezia_anchor_gets_only_scoped_kachur_exception(self):
        def required(*args, **kwargs):
            if args[:5] == ("iptables", "-w", "2", "-S", "OUTPUT"):
                return "-P OUTPUT ACCEPT\n-A OUTPUT -j amnvpn.anchors\n"
            if args[:5] == ("ip6tables", "-w", "2", "-S", "OUTPUT"):
                return "-P OUTPUT ACCEPT\n"
            return ""

        with patch.object(network, "required", side_effect=required) as req, patch.object(
            network, "run", return_value=SimpleNamespace(returncode=1, stdout=""),
        ), patch.object(
            network, "peer_endpoints",
            return_value=[(ipaddress.ip_address("48.102.252.233"), 37649)],
        ), patch.object(network, "route_device", return_value="wlp2s0"):
            network.install_firewall_bypass(False)

        commands = [call.args for call in req.call_args_list]
        self.assertIn(
            ("iptables", "-w", "2", "-A", "KACHUR_OUT", "-o", "kch0", "-j", "ACCEPT"),
            commands,
        )
        self.assertIn(
            ("iptables", "-w", "2", "-A", "KACHUR_OUT", "-o", "wlp2s0", "-d",
             "48.102.252.233", "-p", "udp", "--dport", "37649", "-j", "ACCEPT"),
            commands,
        )
        self.assertIn(
            ("iptables", "-w", "2", "-I", "OUTPUT", "1", "-j", "KACHUR_OUT"),
            commands,
        )
        self.assertFalse(any("amnvpn.anchors" in arg for call in commands for arg in call))

    def test_peer_endpoints_parse_ipv4_and_ipv6(self):
        output = (
            "peer4 48.102.252.233:37649\n"
            "peer6 [2001:db8::1]:51820\n"
        )
        with patch.object(network, "required", return_value=output):
            self.assertEqual(
                network.peer_endpoints(),
                [
                    (ipaddress.ip_address("48.102.252.233"), 37649),
                    (ipaddress.ip_address("2001:db8::1"), 51820),
                ],
            )

    def test_remove_firewall_bypass_leaves_amnezia_anchor_intact(self):
        state = {"jump": True}

        def required(*args, **kwargs):
            if args[:5] == ("iptables", "-w", "2", "-S", "OUTPUT"):
                kachur = "-A OUTPUT -j KACHUR_OUT\n" if state["jump"] else ""
                return "-P OUTPUT ACCEPT\n" + kachur + "-A OUTPUT -j amnvpn.anchors\n"
            return ""

        def run(*args, **kwargs):
            if args[:5] == ("iptables", "-w", "2", "-D", "OUTPUT"):
                state["jump"] = False
                return SimpleNamespace(returncode=0, stdout="")
            if args[:5] == ("iptables", "-w", "2", "-S", "KACHUR_OUT"):
                return SimpleNamespace(returncode=0, stdout="-N KACHUR_OUT")
            return SimpleNamespace(returncode=1, stdout="")

        with patch.object(network, "required", side_effect=required), patch.object(
            network, "run", side_effect=run,
        ) as run_mock:
            network.remove_firewall_bypass()

        commands = [call.args for call in run_mock.call_args_list]
        self.assertIn(("iptables", "-w", "2", "-D", "OUTPUT", "-j", "KACHUR_OUT"), commands)
        self.assertIn(("iptables", "-w", "2", "-S", "KACHUR_OUT"), commands)
        self.assertFalse(any("amnvpn.anchors" in arg for call in commands for arg in call))

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
            ), patch.object(network, "required", return_value=""), patch.object(
                network, "run", return_value=SimpleNamespace(stdout="")
            ) as run, patch.object(network, "remove_firewall_bypass"):
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
            ) as run, patch.object(network, "remove_firewall_bypass") as firewall:
                network.cleanup()
                run.assert_not_called()
                firewall.assert_called_once_with()

    def test_probe_refuses_to_try_https_on_wrong_route(self):
        with patch.object(network, "check_amnezia"), patch.object(network, "device", return_value=True), patch.object(
            network, "route_device", return_value="wlp2s0",
        ), patch.object(network, "required") as run:
            with self.assertRaisesRegex(network.NetworkError, "не проходит через kch0"):
                network.probe(1000)
            run.assert_not_called()
