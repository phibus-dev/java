const form = document.getElementById('test-form');
const startButton = document.getElementById('start');
const cancelButton = document.getElementById('cancel');
const message = document.getElementById('form-message');
const panel = document.getElementById('progress-panel');
const canvas = document.getElementById('speed-chart');
const context = canvas.getContext('2d');
let currentTestId = null;
let eventSource = null;
let speedSamples = [];

function requestPayload() {
  return {
    endpoint: document.getElementById('endpoint').value.trim(),
    bucket: document.getElementById('bucket').value.trim(),
    region: document.getElementById('region').value.trim(),
    accessKey: document.getElementById('accessKey').value,
    secretKey: document.getElementById('secretKey').value,
    pathStyleAccess: document.getElementById('pathStyleAccess').checked,
    objectKey: document.getElementById('objectKey').value.trim(),
    objectSizeMiB: Number(document.getElementById('objectSizeMiB').value),
    partSizeMiB: Number(document.getElementById('partSizeMiB').value),
    operation: 'UPLOAD'
  };
}

async function jsonRequest(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `HTTP ${response.status}`);
  }
  return response.json();
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  startButton.disabled = true;
  message.textContent = 'Создание теста…';
  speedSamples = [];
  drawChart();
  try {
    const run = await jsonRequest('/api/tests', {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(requestPayload())
    });
    currentTestId = run.id;
    panel.hidden = false;
    cancelButton.disabled = false;
    subscribe(run.id);
    updateProgress(run);
  } catch (error) {
    message.textContent = error.message;
    startButton.disabled = false;
  }
});

document.getElementById('load-buckets').addEventListener('click', async () => {
  message.textContent = 'Получение списка бакетов…';
  try {
    const buckets = await jsonRequest('/api/buckets', {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(requestPayload())
    });
    if (buckets.length === 0) message.textContent = 'Доступных бакетов не найдено';
    else {
      document.getElementById('bucket').value = buckets[0];
      message.textContent = `Найдено бакетов: ${buckets.length}. Выбран ${buckets[0]}`;
    }
  } catch (error) { message.textContent = error.message; }
});

cancelButton.addEventListener('click', async () => {
  if (!currentTestId) return;
  try {
    const run = await jsonRequest(`/api/tests/${currentTestId}/cancel`, {method: 'POST'});
    updateProgress(run);
  } catch (error) { message.textContent = error.message; }
});

function subscribe(id) {
  if (eventSource) eventSource.close();
  eventSource = new EventSource(`/api/tests/${id}/events`);
  eventSource.addEventListener('progress', (event) => {
    const run = JSON.parse(event.data);
    updateProgress(run);
    if (run.status === 'COMPLETED' || run.status === 'FAILED' || run.status === 'CANCELLED') finishRun();
  });
  eventSource.onerror = () => {
    if (eventSource) eventSource.close();
  };
}

function updateProgress(run) {
  document.getElementById('status').textContent = run.status;
  document.getElementById('percent').textContent = `${run.percent.toFixed(1)}%`;
  document.getElementById('bytes').textContent = `${(run.bytesTransferred / 1048576).toFixed(1)} / ${(run.totalBytes / 1048576).toFixed(1)} MiB`;
  document.getElementById('current-speed').textContent = `${run.currentSpeedMiBps.toFixed(2)} MiB/s`;
  document.getElementById('average-speed').textContent = `${run.averageSpeedMiBps.toFixed(2)} MiB/s`;
  document.getElementById('progress-bar').style.width = `${run.percent}%`;
  document.getElementById('progress-message').textContent = `${run.message} · части ${run.completedParts}/${run.totalParts}`;
  if (run.status === 'RUNNING' && run.currentSpeedMiBps > 0) {
    speedSamples.push(run.currentSpeedMiBps);
    if (speedSamples.length > 60) speedSamples.shift();
    drawChart();
  }
}

function finishRun() {
  startButton.disabled = false;
  cancelButton.disabled = true;
  message.textContent = 'Тест завершён';
  if (eventSource) eventSource.close();
  refreshHistory();
}

async function refreshHistory() {
  try {
    const runs = await jsonRequest('/api/tests');
    document.getElementById('history').innerHTML = runs.map(run => `<tr>
      <td>${new Date(run.createdAt).toLocaleString()}</td><td>${escapeHtml(run.endpoint)} / ${escapeHtml(run.bucket)}</td>
      <td>${run.status}</td><td>${(run.totalBytes / 1048576).toFixed(1)} MiB</td>
      <td>${run.averageSpeedMiBps.toFixed(2)} MiB/s</td></tr>`).join('');
  } catch (_) { /* history refresh is non-critical */ }
}

function drawChart() {
  const width = canvas.width;
  const height = canvas.height;
  context.clearRect(0, 0, width, height);
  context.strokeStyle = '#dfe5eb';
  context.lineWidth = 1;
  for (let i = 1; i < 5; i++) {
    const y = i * height / 5;
    context.beginPath(); context.moveTo(0, y); context.lineTo(width, y); context.stroke();
  }
  if (speedSamples.length < 2) return;
  const max = Math.max(...speedSamples, 1);
  context.strokeStyle = '#1769cf';
  context.lineWidth = 4;
  context.beginPath();
  speedSamples.forEach((value, index) => {
    const x = index * width / (speedSamples.length - 1);
    const y = height - (value / max) * (height - 20) - 10;
    if (index === 0) context.moveTo(x, y); else context.lineTo(x, y);
  });
  context.stroke();
}

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]));
}
