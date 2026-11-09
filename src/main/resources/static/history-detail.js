(() => {
  const button = document.getElementById('rerun-button');
  const result = document.getElementById('result');
  button?.addEventListener('click', async event => {
    button.disabled = true;
    try {
      const response = await fetch(`/api/history/${encodeURIComponent(event.currentTarget.dataset.runId)}/rerun`, {method: 'POST'});
      if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
      const run = await response.json();
      result.textContent = `Новый тест создан: ${run.id}`;
      window.EvoUI?.notify('Повторный тест создан', 'success');
    } catch (error) {
      result.textContent = `Ошибка повторного запуска: ${error.message}`;
      window.EvoUI?.notify('Не удалось повторить тест', 'error');
    } finally {
      button.disabled = false;
    }
  });
})();
