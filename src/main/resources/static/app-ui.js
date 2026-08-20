(() => {
  const KEY = 'evo-snt-ui-view';
  const MODES = [
    {id:'A', name:'Карточный', title:'Карточный (рекомендуемый)'},
    {id:'B', name:'Инфоблоки', title:'Информационные блоки'},
    {id:'C', name:'Компактный', title:'Компактный с иконками'},
    {id:'D', name:'Табличный', title:'Табличный фокус'}
  ];

  function currentMode() {
    const saved = localStorage.getItem(KEY);
    return MODES.some(m => m.id === saved) ? saved : 'A';
  }

  function applyMode(mode) {
    document.documentElement.dataset.uiView = mode;
    localStorage.setItem(KEY, mode);
    document.querySelectorAll('[data-ui-mode]').forEach(button => {
      const selected = button.dataset.uiMode === mode;
      button.classList.toggle('active', selected);
      button.setAttribute('aria-pressed', selected ? 'true' : 'false');
    });
    window.dispatchEvent(new Event('resize'));
  }

  function makeSwitcher() {
    if (document.querySelector('.ui-view-switcher')) return;
    const host = document.querySelector('main.container') || document.querySelector('main') || document.body;
    const switcher = document.createElement('section');
    switcher.className = 'ui-view-switcher';
    switcher.setAttribute('aria-label', 'Вариант представления');
    switcher.innerHTML = `<span class="ui-view-label">Вариант представления:</span><div class="ui-view-buttons">${MODES.map(m => `<button type="button" class="ui-view-button" data-ui-mode="${m.id}" title="${m.title}"><strong>${m.id}</strong><span>${m.name}</span></button>`).join('')}</div>`;

    const firstPanel = host.querySelector('.panel');
    const navs = host.querySelectorAll('.corporate-nav');
    if (navs.length) {
      navs[navs.length - 1].insertAdjacentElement('afterend', switcher);
    } else if (firstPanel) {
      firstPanel.insertAdjacentElement('beforebegin', switcher);
    } else {
      host.prepend(switcher);
    }
    switcher.addEventListener('click', e => {
      const button = e.target.closest('[data-ui-mode]');
      if (button) applyMode(button.dataset.uiMode);
    });
    applyMode(currentMode());
  }

  document.documentElement.dataset.uiView = currentMode();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', makeSwitcher);
  else makeSwitcher();
})();
