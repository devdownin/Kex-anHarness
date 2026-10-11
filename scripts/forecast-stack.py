#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Kex Agent AI Contributors
"""Read-only local stack diagnosis and explicit demo metric setup; never print secrets."""
import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = {'kex_list_forecastable_metrics', 'kex_metric_history', 'kex_forecast_metric',
         'kex_list_predicted_threshold_breaches', 'kex_get_forecast_quality'}


def compose(*args):
    command = ['docker', 'compose']
    if (ROOT / '.env').exists():
        command += ['--env-file', '.env']
    command += ['--env-file', '.forecast-stack/.env', '-f', 'docker-compose.yml', '-f', 'compose/forecasts.yml']
    return subprocess.run(command + list(args), cwd=ROOT, capture_output=True, text=True,
                          check=True, timeout=60).stdout


def config():
    return json.loads(compose('config', '--format', 'json'))


def base_url(settings, service):
    port = settings['services'][service]['ports'][0]['published']
    return f'http://127.0.0.1:{port}'


def request(url, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=20) as response:
        raw = response.read()
    # KafkaExplorer répond 200 sans corps à `POST /api/metrics` : un json.load inconditionnel y
    # levait une JSONDecodeError et interrompait la démo juste après la création de la métrique.
    return json.loads(raw) if raw.strip() else None


def mcp_post(url, headers, body, session=None):
    """Streamable HTTP : hors `initialize`, une requête sans Mcp-Session-Id est refusée en 400."""
    if session:
        headers = dict(headers, **{'Mcp-Session-Id': session})
    request_ = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(request_, timeout=20) as response:
        return response.headers.get('Mcp-Session-Id'), response.read().decode()


def mcp_result(raw):
    events = [json.loads(line[5:].strip()) for line in raw.splitlines() if line.startswith('data:')]
    return events[-1] if events else json.loads(raw)


def check_mcp(settings):
    url = base_url(settings, 'explorer') + '/mcp'
    initialize = {'jsonrpc': '2.0', 'id': 0, 'method': 'initialize', 'params': {
        'protocolVersion': '2025-06-18', 'capabilities': {},
        'clientInfo': {'name': 'forecast-stack', 'version': '1'}}}
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
    try:
        mcp_post(url, headers, initialize)
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise urllib.error.HTTPError(url, error.code, 'MCP refused request', None, None) from error
    else:
        raise ValueError('MCP accepted an unauthenticated request')
    headers['Authorization'] = 'Bearer ' + settings['services']['explorer']['environment']['EXPLORER_MCP_AUTH_TOKEN']
    session, _ = mcp_post(url, headers, initialize)
    mcp_post(url, headers, {'jsonrpc': '2.0', 'method': 'notifications/initialized'}, session)
    _, raw = mcp_post(url, headers, {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list', 'params': {}}, session)
    names = {tool['name'] for tool in mcp_result(raw).get('result', {}).get('tools', [])}
    if not TOOLS <= names:
        raise ValueError('Forecast MCP tool catalogue incomplete')


def diagnose(settings):
    failed = False
    actions = {
        'kafka': 'Vérifier le volume Kafka et les logs du broker.',
        'forecast-postgres': 'Vérifier les identifiants conservés et le volume PostgreSQL.',
        'forecast-model-prefetch': 'Vérifier accès Internet, espace disque et téléchargement du modèle.',
        'timesfm': 'Vérifier RAM, modèle préchargé et token TimesFM.',
        'explorer': 'Vérifier le YAML approuvé et les dépendances PostgreSQL/TimesFM.',
        'agent': 'Vérifier clé API, fournisseur LLM et connexion MCP.',
    }
    rows = compose('ps', '--all', '--format', 'json')
    try:
        rows = json.loads(rows)
        rows = rows if isinstance(rows, list) else [rows]
    except json.JSONDecodeError:
        rows = [json.loads(row) for row in rows.splitlines() if row.strip()]
    by_service = {row['Service']: row for row in rows}
    for service, action in actions.items():
        row = by_service.get(service, {})
        ok = ((row.get('State') == 'exited' and row.get('ExitCode') == 0) if service.endswith('prefetch')
              else row.get('State') == 'running' and row.get('Health', '') in ('', 'healthy'))
        print(f"{'OK' if ok else 'BLOQUÉ'} · {service} · {row.get('Health') or row.get('State', 'absent')}")
        if not ok:
            failed = True
            print('  Action : ' + action)
    checks = [
        ('PostgreSQL', lambda: compose('exec', '-T', 'forecast-postgres', 'sh', '-c',
          'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U forecasts -d forecasts -v ON_ERROR_STOP=1 -Atc "SELECT 1"'), actions['forecast-postgres']),
        ('TimesFM prêt', lambda: compose('exec', '-T', 'timesfm', 'python', '-c',
          "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health/ready', timeout=3).read()"), actions['timesfm']),
        ('Authentification et cinq outils MCP', lambda: check_mcp(settings), 'Vérifier le token partagé et le pilote de prévisions.'),
    ]
    for name, check, action in checks:
        try:
            check()
            print('OK · ' + name)
        except (subprocess.SubprocessError, OSError, ValueError) as error:
            if isinstance(error, urllib.error.HTTPError):
                print(f'  HTTP {error.code} · ' + ('TLS exigé : vérifier EXPLORER_MCP_REQUIRETLS pour cette stack locale.' if error.code == 426 else 'Vérifier la configuration du serveur MCP.'))
            failed = True
            print('BLOQUÉ · ' + name + '\n  Action : ' + action)
    try:
        env = settings['services']['agent']['environment']
        data = request(base_url(settings, 'agent') + '/api/agent/forecasts/readiness', token=env['KEX_AGENT_API_KEY'])
        if not data.get('connected') or data.get('missingTools') or data.get('unavailable') or data.get('catalogComplete') is not True:
            failed = True
            print('BLOQUÉ · Connexion MCP de l’agent · vérifier les permissions et le token.')
            for check in data.get('checks') or []:
                if check.get('state') not in ('READY', 'NOT_CHECKED'):
                    print(f"  {check.get('id')} · {check.get('state')} · {check.get('detail')}")
            if data.get('unavailable'):
                print('  Motif : ' + str(data['unavailable']))
        else:
            print('OK · Connexion MCP de l’agent')
        if not data.get('ready'):
            print('À CONFIGURER · Séries autorisées · ouvrir Metrics Forecast, approuver et exporter les sources.')
    except (OSError, ValueError):
        failed = True
        print('BLOQUÉ · API agent · vérifier clé API et état du service.')
    return 1 if failed else 0


def demo(settings):
    """Create real data and an eligible metric; no automatic source approval or fake history."""
    topic = 'forecast.demo.orders'
    compose('--profile', 'forecast-demo', 'run', '--rm', '--no-deps', 'forecast-demo-seed')
    base = base_url(settings, 'explorer')
    metrics = request(base + '/api/metrics')
    if not any(row['id'] == 'forecast-demo-lag' for row in metrics):
        request(base + '/api/metrics', {'id': 'forecast-demo-lag', 'name': 'Démo · Retard consommateur',
                'type': 'GAUGE', 'templateType': 'CONSUMER_TIME_LAG', 'executionMode': 'TEMPLATE_BOUNDED_SCAN',
                'templateParams': {'topic': topic, 'group': 'forecast-demo', 'aggregation': 'MAX'}})
    request(base + '/api/metrics/forecast-demo-lag/refresh', {})
    candidate = next(row for row in request(base + '/api/forecasts/candidates')['metrics'] if row['metricId'] == 'forecast-demo-lag')
    if not candidate['eligible']:
        raise ValueError('Demo metric is not eligible; inspect candidate blockers in KafkaExplorer')
    print('Démo prête · forecast.demo.orders · groupe forecast-demo · métrique forecast-demo-lag')
    print('Ouvrir Metrics Forecast > Configure a forecast. Approuver les sources puis exporter le YAML.')
    print('Aucun historique synthétique ajouté ; la collecte démarre après déploiement du YAML approuvé.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['diagnose', 'wait', 'demo'])
    parser.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args()
    try:
        settings = config()
        if args.mode == 'demo':
            demo(settings)
            return 0
        if args.mode == 'wait':
            deadline = time.monotonic() + args.timeout
            while True:
                print('--- Progression du démarrage ---', flush=True)
                if diagnose(settings) == 0:
                    return 0
                if time.monotonic() >= deadline:
                    return 1
                time.sleep(5)
        return diagnose(settings)
    except (OSError, subprocess.SubprocessError, ValueError, StopIteration) as error:
        print('Diagnostic interrompu : ' + reason(error) + '.', file=sys.stderr)
        print('Vérifier Docker Compose, les fichiers .env et les logs des services.', file=sys.stderr)
        return 1


def reason(error):
    """La cause d'une interruption, sans URL complète ni environnement : jamais de secret."""
    if isinstance(error, urllib.error.HTTPError):
        return f'HTTP {error.code} sur {urllib.parse.urlsplit(error.url or "").path or "?"}'
    if isinstance(error, subprocess.CalledProcessError):
        last = (error.stderr or '').strip().splitlines()[-1:] or ['']
        return f'commande Docker Compose en échec (code {error.returncode}) {last[0]}'.strip()
    if isinstance(error, subprocess.TimeoutExpired):
        return 'commande Docker Compose trop longue'
    if isinstance(error, StopIteration):
        return 'métrique de démonstration absente des candidats KafkaExplorer'
    if isinstance(error, json.JSONDecodeError):
        return 'réponse JSON illisible'
    if isinstance(error, urllib.error.URLError):
        return f'service injoignable ({error.reason})'
    return f'{type(error).__name__} : {error}'


if __name__ == '__main__':
    sys.exit(main())
