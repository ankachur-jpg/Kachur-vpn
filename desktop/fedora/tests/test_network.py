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

    def test_bypass_json_parser_normalizes_and_deduplicates(self):
        networks = network.parse_bypass_document(
            '{"version":1,"bypass_vpn":["203.0.113.7","1.1.1.1/32","203.0.113.7/32"]}'
        )
        self.assertEqual(
            [str(item) for item in networks],
            ["1.1.1.1/32", "203.0.113.7/32"],
        )

    def test_bypass_parser_rejects_loopback(self):
        with self.assertRaisesRegex(network.NetworkError, "loopback"):
            network.parse_bypass_document(
                '{"version":1,"bypass_vpn":["127.0.0.0/8"]}'
            )

    def test_dns_server_cannot_be_in_bypass_list(self):
        networks = [ipaddress.ip_network("1.1.1.0/24")]
        with self.assertRaisesRegex(network.NetworkError, "DNS-сервер"):
            network.ensure_dns_not_bypassed(networks, ["1.1.1.1"])

    def test_probe_target_moves_away_from_bypass_network(self):
        networks = [ipaddress.ip_network("1.1.1.1/32")]
        self.assertEqual(network.select_probe_target(networks), "1.0.0.1")

    def test_bypass_parser_rejects_default_route(self):
        with self.assertRaisesRegex(network.NetworkError, "по умолчанию"):
            network.parse_bypass_document(
                '{"version":1,"bypass_vpn":["::/0"]}'
            )

    def test_bypass_rules_are_recorded_before_installation(self):
        with TemporaryDirectory() as directory:
            marker = Path(directory) / "bypass-owned"
            networks = [
                ipaddress.ip_network("203.0.113.0/24"),
                ipaddress.ip_network("2001:db8::/32"),
            ]
            with patch.object(network, "BYPASS_OWNED", marker), patch.object(
                network, "required", return_value=""
            ) as required:
                network.add_bypass_rules(networks)
            self.assertEqual(
                marker.read_text(encoding="ascii"),
                '["203.0.113.0/24", "2001:db8::/32"]',
            )
        self.assertEqual(
            [call.args for call in required.call_args_list],
            [
                ("ip", "-4", "rule", "add", "pref", "10400", "to",
                 "203.0.113.0/24", "lookup", "main", "protocol", "242"),
                ("ip", "-6", "rule", "add", "pref", "10400", "to",
                 "2001:db8::/32", "lookup", "main", "protocol", "242"),
            ],
        )

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

    @patch.object(network.shutil, "which", return_value="/usr/bin/iptables")
    def test_stale_amnezia_anchor_gets_only_scoped_kachur_exception(self, _which):
        def required(*args, **kwargs):
            if args[:5] == ("iptables", "-w", "2", "-S", "OUTPUT"):
                return (
                    "-P OUTPUT ACCEPT\n"
                    "-A OUTPUT -m conntrack --ctstate ESTABLISHED -j ACCEPT\n"
                    "-A OUTPUT -j amnvpn.anchors\n"
                )
            if args[:5] == ("ip6tables", "-w", "2", "-S", "OUTPUT"):
                return "-P OUTPUT ACCEPT\n"
            return ""

        with patch.object(network, "required", side_effect=required) as req, patch.object(
            network, "run", return_value=SimpleNamespace(returncode=1, stdout=""),
        ), patch.object(
            network, "peer_endpoints",
            return_value=[(ipaddress.ip_address("48.102.252.233"), 37649)],
        ), patch.object(network, "route_device", return_value="wlp2s0"):
            network.install_firewall_bypass(
                False, [ipaddress.ip_network("203.0.113.0/24")], 1000
            )

        commands = [call.args for call in req.call_args_list]
        self.assertIn(
            ("iptables", "-w", "2", "-A", "KACHUR_OUT", "-o", "kch0", "-j", "ACCEPT"),
            commands,
        )
        self.assertIn(
            ("iptables", "-w", "2", "-A", "KACHUR_OUT", "-o", "wlp2s0", "-d",
             "48.102.252.233", "-p", "udp", "--dport", "37649", "-m", "mark",
             "--mark", network.MARK, "-j", "ACCEPT"),
            commands,
        )
        self.assertIn(
            ("iptables", "-w", "2", "-A", "KACHUR_OUT", "-o", "wlp2s0", "-d",
             "203.0.113.0/24", "-j", "ACCEPT"),
            commands,
        )
        self.assertIn(
            ("iptables", "-w", "2", "-I", "OUTPUT", "2", "-j", "KACHUR_OUT"),
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

    @patch.object(network.shutil, "which", return_value="/usr/bin/iptables")
    def test_remove_firewall_bypass_leaves_amnezia_anchor_intact(self, _which):
        state = {"jump": True}

        def required(*args, **kwargs):
            if args[:5] == ("iptables", "-w", "2", "-S", "OUTPUT"):
                kachur = "-A OUTPUT -j KACHUR_OUT\n" if state["jump"] else ""
                return "-P OUTPUT ACCEPT\n" + kachur + "-A OUTPUT -j amnvpn.anchors\n"
            if args[:5] == ("iptables", "-w", "2", "-D", "OUTPUT"):
                state["jump"] = False
                return ""
            return ""

        def run(*args, **kwargs):
            if args[:5] == ("iptables", "-w", "2", "-S", "KACHUR_OUT"):
                return SimpleNamespace(returncode=0, stdout="-N KACHUR_OUT")
            return SimpleNamespace(returncode=1, stdout="")

        with patch.object(
            network, "required", side_effect=required,
        ) as required_mock, patch.object(
            network, "run", side_effect=run,
        ) as run_mock:
            network.remove_firewall_bypass()

        required_commands = [call.args for call in required_mock.call_args_list]
        run_commands = [call.args for call in run_mock.call_args_list]
        self.assertIn(
            ("iptables", "-w", "2", "-D", "OUTPUT", "-j", "KACHUR_OUT"),
            required_commands,
        )
        self.assertIn(("iptables", "-w", "2", "-S", "KACHUR_OUT"), run_commands)
        self.assertFalse(any(
            "amnvpn.anchors" in arg
            for call in required_commands + run_commands for arg in call
        ))

    def test_offline_test_has_systemd_deadline_and_independent_cleanup(self):
        with patch.object(network, "device", return_value=False), patch.object(
            network, "check_amnezia",
        ), patch.object(network, "run", return_value=SimpleNamespace(returncode=0)) as run, patch.object(
            network, "required", return_value="Откат Kachur: OK.\n",
        ):
            self.assertEqual(network.start("offline-test", 1000), 0)
            args = run.call_args.args
            self.assertIn("--property=RuntimeMaxSec=95s", args)
            self.assertIn("--wait", args)
            self.assertIn(f"--property=ExecStopPost={SCRIPT} cleanup", args)

    def test_cleanup_removes_policy_even_when_tun_disappeared(self):
        with TemporaryDirectory() as directory:
            marker = Path(directory) / "owned"
            config = Path(directory) / "kch0.conf"
            marker.write_text("-4\n-6")
            config.write_text("test profile")
            with patch.object(network, "OWNED", marker), patch.object(
                network, "CONFIG", config,
            ), patch.object(
                network, "BYPASS_OWNED", Path(directory) / "bypass-owned",
            ), patch.object(
                network, "device", return_value=False,
            ), patch.object(network, "required", return_value=""), patch.object(
                network, "run", return_value=SimpleNamespace(stdout="")
            ) as run, patch.object(network, "remove_firewall_bypass"):
                network.cleanup()
                self.assertFalse(marker.exists())
                self.assertFalse(config.exists())
                self.assertEqual([call.args for call in run.call_args_list], [
                    ("ip", "-4", "rule", "delete", *network.RULE),
                    ("ip", "-4", "route", "flush", "table", network.TABLE, "proto", network.PROTOCOL),
                    ("ip", "-6", "rule", "delete", *network.RULE),
                    ("ip", "-6", "route", "flush", "table", network.TABLE, "proto", network.PROTOCOL),
                    ("ip", "-4", "route", "show", "table", network.TABLE),
                    ("ip", "-6", "route", "show", "table", network.TABLE),
                ])

    def test_cleanup_recovers_reserved_routes_without_ownership_marker(self):
        with TemporaryDirectory() as directory:
            config = Path(directory) / "kch0.conf"
            config.write_text("test profile")
            with patch.object(network, "OWNED", Path(directory) / "missing"), patch.object(
                network, "CONFIG", config,
            ), patch.object(
                network, "BYPASS_OWNED", Path(directory) / "bypass-owned",
            ), patch.object(
                network, "device", return_value=False,
            ), patch.object(
                network, "run", return_value=SimpleNamespace(returncode=0, stdout="")
            ) as run, patch.object(
                network, "required", return_value=""
            ), patch.object(network, "remove_firewall_bypass") as firewall:
                network.cleanup()
            self.assertFalse(config.exists())

        self.assertEqual([call.args for call in run.call_args_list], [
            ("ip", "-4", "rule", "delete", *network.RULE),
            ("ip", "-4", "route", "flush", "table", network.TABLE, "proto", network.PROTOCOL),
            ("ip", "-6", "rule", "delete", *network.RULE),
            ("ip", "-6", "route", "flush", "table", network.TABLE, "proto", network.PROTOCOL),
            ("ip", "-4", "route", "show", "table", network.TABLE),
            ("ip", "-6", "route", "show", "table", network.TABLE),
        ])
        firewall.assert_called_once_with()

    def test_probe_refuses_to_try_https_on_wrong_route(self):
        with patch.object(network, "check_amnezia"), patch.object(network, "load_bypass_networks", return_value=[]), patch.object(network, "device", return_value=True), patch.object(
            network, "route_device", return_value="wlp2s0",
        ), patch.object(network, "required") as run:
            with self.assertRaisesRegex(network.NetworkError, "не проходит через kch0"):
                network.probe(1000)
            run.assert_not_called()

    def test_probe_checks_system_dns_with_https_bound_to_tunnel(self):
        def required(*args, **kwargs):
            if args[0] == "resolvectl":
                return "cloudflare-dns.com: 1.1.1.1\n"
            return ""

        with patch.object(network, "check_amnezia"), patch.object(
            network, "load_bypass_networks", return_value=[],
        ), patch.object(network, "device", return_value=True,
        ), patch.object(network, "route_device", return_value="kch0"), patch.object(
            network, "required", side_effect=required,
        ) as run:
            network.probe(1000)

        direct, dns, by_name = (call.args for call in run.call_args_list)
        self.assertIn("--resolve", direct)
        self.assertEqual(dns[:5], ("resolvectl", "query", "-4", "-i", "kch0"))
        self.assertEqual(by_name[0], "curl")
        self.assertIn("if!kch0", by_name)
        self.assertIn("--resolve", by_name)
        self.assertIn("cloudflare-dns.com:443:1.1.1.1", by_name)

    def test_probe_skips_hostname_https_when_dns_answer_is_bypassed(self):
        def required(*args, **kwargs):
            if args[0] == "resolvectl":
                return "cloudflare-dns.com: 1.1.1.1\n"
            return ""

        with patch.object(network, "check_amnezia"), patch.object(
            network, "load_bypass_networks",
            return_value=[ipaddress.ip_network("1.1.1.1/32")],
        ), patch.object(network, "device", return_value=True), patch.object(
            network, "route_device", return_value="kch0",
        ), patch.object(network, "required", side_effect=required) as run:
            network.probe(1000)

        self.assertEqual(len(run.call_args_list), 2)
        self.assertTrue(any("1.0.0.1" in arg for arg in run.call_args_list[0].args))

    def test_connect_worker_exits_when_amnezia_appears(self):
        with patch.object(network.os, "geteuid", return_value=0), patch.object(
            network, "configure",
        ), patch.object(network, "probe"), patch.object(network, "notify_ready") as ready, patch.object(
            network, "device", side_effect=[True, False, True, True],
        ) as device, patch.object(network.time, "sleep") as sleep:
            self.assertEqual(network.main(["worker", "connect", "1000"]), 0)

        ready.assert_called_once_with()
        self.assertEqual([call.args[0] for call in device.call_args_list], ["kch0", "amn0"] * 2)
        sleep.assert_called_once_with(1)
