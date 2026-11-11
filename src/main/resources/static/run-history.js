(() => {
  'use strict';

  const result = document.getElementById('rerun-result');

  async function rerun(button) {
    button.disabled = true;
    try {
      const url = '/api/run-history/' + encodeURIComponent(button.dataset.runId) + '/rerun';
      const response = await fetch(url, {method: 'POST'});
      if (!response.ok) throw new Error((await response.text()) || 'HTTP ' + response.status);
      const run = await response.json();
      if (result) {
        result.innerHTML = 'Новый запуск <strong>' + escapeHtml(run.runId)
          + '</strong> создан. <a href="' + escapeHtml(run.monitorUrl) + '">Открыть мониторинг</a>';
      }
      window.EvoUI?.notify('Повторный тест создан', 'success');
    } catch (error) {
      if (result) result.textContent = 'Ошибка повторного запуска: ' + error.message;
      window.EvoUI?.notify('Не удалось повторить тест', 'error');
    } finally {
      button.disabled = false;
    }
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, character => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[character]);
  }

  document.querySelectorAll('.rerun-run').forEach(button => {
    button.addEventListener('click', () => rerun(button));
  });
})();
