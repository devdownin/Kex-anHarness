# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Kex Agent AI Contributors
import contextlib
import importlib.util
import io
import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('forecast_stack', Path(__file__).resolve().parents[1] / 'scripts/forecast-stack.py')
stack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stack)


class McpBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.auth_required = True
        self.tools = stack.TOOLS.copy()
        self.sse = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                if owner.auth_required and self.headers.get('Authorization') != 'Bearer private-token':
                    self.send_response(401)
                    self.end_headers()
                    return
                # Comme le serveur Streamable HTTP de l'Explorer : hors initialize, sans session → 400.
                method = json.loads(self.rfile.read(int(self.headers['Content-Length'])))['method']
                if method == 'initialize':
                    self.send_response(200)
                    self.send_header('Mcp-Session-Id', 'session-1')
                    self.end_headers()
                    self.wfile.write(b'{"jsonrpc": "2.0", "id": 0, "result": {}}')
                    return
                if self.headers.get('Mcp-Session-Id') != 'session-1':
                    self.send_response(400)
                    self.end_headers()
                    return
                if method.startswith('notifications/'):
                    self.send_response(202)
                    self.end_headers()
                    return
                self.send_response(200)
                self.end_headers()
                data = json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': {'tools': [{'name': name} for name in owner.tools]}})
                self.wfile.write((('event: message\ndata: ' + data + '\n\n') if owner.sse else data).encode())

        self.server = HTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.settings = {'services': {'explorer': {'ports': [{'published': self.server.server_port}],
                         'environment': {'EXPLORER_MCP_AUTH_TOKEN': 'private-token'}}}}

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def test_authenticated_catalog_json_and_sse(self):
        stack.check_mcp(self.settings)
        self.sse = True
        stack.check_mcp(self.settings)

    def test_public_mcp_is_a_failure_even_with_all_tools(self):
        self.auth_required = False
        with self.assertRaises(ValueError):
            stack.check_mcp(self.settings)

    def test_missing_forecast_tool_is_a_failure(self):
        self.tools.remove('kex_forecast_metric')
        with self.assertRaises(ValueError):
            stack.check_mcp(self.settings)


class DiagnosticTest(unittest.TestCase):
    def test_empty_catalog_is_configuration_not_infrastructure_failure(self):
        rows = [{'Service': name, 'State': 'running', 'Health': 'healthy'} for name in
                ['kafka', 'forecast-postgres', 'timesfm', 'explorer', 'agent']]
        rows += [{'Service': 'forecast-model-prefetch', 'State': 'exited', 'ExitCode': 0}]
        settings = {'services': {'agent': {'ports': [{'published': 8081}], 'environment': {'KEX_AGENT_API_KEY': 'private-agent-token'}}}}
        output = io.StringIO()
        with patch.object(stack, 'compose', side_effect=lambda *args: json.dumps(rows) if args[0] == 'ps' else '1'), \
             patch.object(stack, 'check_mcp'), \
             patch.object(stack, 'request', return_value={'connected': True, 'catalogComplete': True, 'ready': False}), \
             contextlib.redirect_stdout(output):
            self.assertEqual(stack.diagnose(settings), 0)
        self.assertIn('À CONFIGURER', output.getvalue())
        self.assertNotIn('private-agent-token', output.getvalue())

    def test_agent_block_names_the_failing_check(self):
        rows = [{'Service': name, 'State': 'running', 'Health': 'healthy'} for name in
                ['kafka', 'forecast-postgres', 'timesfm', 'explorer', 'agent']]
        rows += [{'Service': 'forecast-model-prefetch', 'State': 'exited', 'ExitCode': 0}]
        settings = {'services': {'agent': {'ports': [{'published': 8081}], 'environment': {'KEX_AGENT_API_KEY': 'private-agent-token'}}}}
        readiness = {'connected': False, 'catalogComplete': False, 'ready': False,
                     'unavailable': 'Découverte MCP indisponible',
                     'checks': [{'id': 'connection', 'state': 'BLOCKED', 'detail': 'Découverte MCP indisponible'},
                                {'id': 'kex_metric_history', 'state': 'NOT_CHECKED', 'detail': 'Découverte non terminée'}]}
        output = io.StringIO()
        with patch.object(stack, 'compose', side_effect=lambda *args: json.dumps(rows) if args[0] == 'ps' else '1'), \
             patch.object(stack, 'check_mcp'), patch.object(stack, 'request', return_value=readiness), \
             contextlib.redirect_stdout(output):
            self.assertEqual(stack.diagnose(settings), 1)
        self.assertIn('connection · BLOCKED · Découverte MCP indisponible', output.getvalue())
        self.assertNotIn('NOT_CHECKED', output.getvalue())

    def test_unhealthy_service_causes_nonzero_status_without_leaking_secrets(self):
        settings = {'services': {'agent': {'ports': [{'published': 8081}], 'environment': {'KEX_AGENT_API_KEY': 'private-agent-token'}}}}
        output = io.StringIO()
        with patch.object(stack, 'compose', return_value='[]'), patch.object(stack, 'check_mcp', side_effect=ValueError('private-token')), \
             patch.object(stack, 'request', side_effect=ValueError('private-agent-token')), contextlib.redirect_stdout(output):
            self.assertEqual(stack.diagnose(settings), 1)
        self.assertIn('BLOQUÉ', output.getvalue())
        self.assertNotIn('private-token', output.getvalue())
        self.assertNotIn('private-agent-token', output.getvalue())


class RequestTest(unittest.TestCase):
    def setUp(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'[{"id": "forecast-demo-lag"}]')

            def do_POST(self):
                # Comme `POST /api/metrics` de KafkaExplorer : 200, sans corps.
                self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(200)
                self.send_header('Content-Length', '0')
                self.end_headers()

        self.server = HTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def test_une_reponse_sans_corps_n_interrompt_pas_la_demo(self):
        self.assertIsNone(stack.request(self.base + '/api/metrics', {'id': 'forecast-demo-lag'}))
        self.assertEqual(stack.request(self.base + '/api/metrics'), [{'id': 'forecast-demo-lag'}])


class InterruptionReasonTest(unittest.TestCase):
    def test_la_cause_est_nommee_sans_url_complete_ni_secret(self):
        http = stack.urllib.error.HTTPError('http://127.0.0.1:8080/api/metrics?token=secret', 400, 'Bad', None, None)
        self.assertEqual(stack.reason(http), 'HTTP 400 sur /api/metrics')
        failed = stack.subprocess.CalledProcessError(1, ['docker'], stderr='warn\nseed: topic introuvable\n')
        self.assertEqual(stack.reason(failed), 'commande Docker Compose en échec (code 1) seed: topic introuvable')
        self.assertIn('absente des candidats', stack.reason(StopIteration()))
        self.assertEqual(stack.reason(ValueError('Demo metric is not eligible')), 'ValueError : Demo metric is not eligible')

    def test_main_affiche_la_cause_au_lieu_d_un_message_generique(self):
        err = io.StringIO()
        with patch.object(stack, 'config', side_effect=StopIteration()), patch.object(sys, 'argv', ['x', 'demo']), \
             contextlib.redirect_stderr(err):
            self.assertEqual(stack.main(), 1)
        self.assertIn('Diagnostic interrompu : métrique de démonstration absente', err.getvalue())


class DemoMetricTest(unittest.TestCase):
    def test_real_seed_is_followed_by_metric_creation_with_exact_source(self):
        calls = []
        def api(url, body=None):
            calls.append((url, body))
            if url.endswith('/api/metrics') and body is None:
                return []
            if url.endswith('/candidates'):
                return {'metrics': [{'metricId': 'forecast-demo-lag', 'eligible': True}]}
            return {}
        settings = {'services': {'explorer': {'ports': [{'published': 18080}]}}}
        with patch.object(stack, 'compose') as seed, patch.object(stack, 'request', side_effect=api), contextlib.redirect_stdout(io.StringIO()):
            stack.demo(settings)
        seed.assert_called_once_with('--profile', 'forecast-demo', 'run', '--rm', '--no-deps', 'forecast-demo-seed')
        created = next(body for url, body in calls if url.endswith('/api/metrics') and body)
        self.assertEqual(created['templateParams']['topic'], 'forecast.demo.orders')
        self.assertEqual(created['templateParams']['group'], 'forecast-demo')

    def test_existing_metric_is_preserved(self):
        def api(url, body=None):
            if url.endswith('/api/metrics'):
                if body is not None:
                    self.fail('Existing metric must not be overwritten')
                return [{'id': 'forecast-demo-lag'}]
            if url.endswith('/candidates'):
                return {'metrics': [{'metricId': 'forecast-demo-lag', 'eligible': True}]}
            return {}
        settings = {'services': {'explorer': {'ports': [{'published': 8080}]}}}
        with patch.object(stack, 'compose'), patch.object(stack, 'request', side_effect=api), contextlib.redirect_stdout(io.StringIO()):
            stack.demo(settings)


if __name__ == '__main__':
    unittest.main()
