const profile = document.getElementById('profileId');
const refresh = document.getElementById('refresh');
const text = (id, value) => { document.getElementById(id).textContent = value ?? '—'; };

async function loadHa() {
  if (!profile.value) return;
  text('message', 'Обновление...');
  try {
    const response = await fetch('/api/clickhouse/ha?profileId=' + encodeURIComponent(profile.value));
    if (!response.ok) throw new Error(await response.text());
    const data = await response.json();
    text('overall', data.overall);
    text('replication', data.replication);
    text('keeper', data.keeper);
    text('nodes', `${data.reachableNodes}/${data.totalNodes}`);
    text('queue', data.queueSize);
    text('delay', `${data.maxDelaySeconds} s`);
    text('lag', data.maxLogLag);
    text('replicas', `${data.readonlyReplicas} / ${data.inactiveReplicas}`);
    const body = document.getElementById('keeper-body');
    body.innerHTML = '';
    for (const node of data.keeperSnapshot.nodes) {
      const tr = document.createElement('tr');
      for (const value of [node.endpoint, node.connected ? 'YES' : 'NO', `${node.latencyMs} ms`, node.rootChildren,
        node.leaderReplicas, node.sessionErrors, node.error || '']) {
        const td = document.createElement('td'); td.textContent = value; tr.appendChild(td);
      }
      body.appendChild(tr);
    }
    text('message', `Снимок: ${data.collectedAt}`);
  } catch (error) {
    text('message', error.message || String(error));
  }
}
refresh.addEventListener('click', loadHa);
profile.addEventListener('change', loadHa);
