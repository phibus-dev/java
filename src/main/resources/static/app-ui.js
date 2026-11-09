(() => {
  const ROLE_LEVEL = {NONE: 0, VIEWER: 1, OPERATOR: 2, ADMIN: 3, OPEN: 99};
  let activeRole = 'NONE';

  function ensureMetadata() {
    if (!document.querySelector('link[rel="icon"]')) {
      document.head.insertAdjacentHTML('beforeend', '<link rel="icon" href="/favicon.svg" type="image/svg+xml">');
    }
    if (!document.querySelector('link[rel="manifest"]')) {
      document.head.insertAdjacentHTML('beforeend', '<link rel="manifest" href="/manifest.webmanifest">');
    }
    if (!document.title.includes('ЭВО.СНТ')) document.title = `${document.title || 'Страница'} — ЭВО.СНТ`;
  }

  function breadcrumbDefinition(path) {
    const exact = {
      '/tasks': [['S3', '/tasks'], ['Задания']],
      '/history': [['S3', '/tasks'], ['История']],
      '/history/compare': [['S3', '/tasks'], ['История', '/history'], ['Сравнение запусков']],
      '/schedules': [['S3', '/tasks'], ['Расписания']],
      '/baselines': [['S3', '/tasks'], ['Baselines']],
      '/settings/s3-profiles': [['S3', '/tasks'], ['Профили']],
      '/clickhouse': [['ClickHouse']],
      '/clickhouse/compare': [['ClickHouse', '/clickhouse'], ['Сравнение запусков']],
      '/clickhouse/replication': [['ClickHouse', '/clickhouse'], ['Репликация']],
      '/clickhouse/replicated-tests': [['ClickHouse', '/clickhouse'], ['Replicated tests']],
      '/clickhouse/failover-tests': [['ClickHouse', '/clickhouse'], ['Failover']],
      '/clickhouse/ha': [['ClickHouse', '/clickhouse'], ['HA dashboard']],
      '/settings/clickhouse-profiles': [['ClickHouse', '/clickhouse'], ['Профили']],
      '/kafka': [['Kafka']],
      '/kafka/m2': [['Kafka', '/kafka'], ['Consumer / Throughput']],
      '/kafka/m3': [['Kafka', '/kafka'], ['Replication / KRaft']],
      '/kafka/m4': [['Kafka', '/kafka'], ['Failover / Resilience']],
      '/settings/kafka-profiles': [['Kafka', '/kafka'], ['Профили']],
      '/distributed-tests': [['Распределённые тесты']],
      '/agents': [['Агенты']],
      '/monitoring': [['Мониторинг']],
      '/settings': [['Настройки']],
      '/settings/keycloak': [['Настройки', '/settings'], ['Keycloak']],
      '/settings/configuration': [['Настройки', '/settings'], ['Импорт / экспорт']],
      '/system': [['Настройки', '/settings'], ['Состояние системы']],
      '/audit': [['Настройки', '/settings'], ['Аудит']],
      '/error': [['Ошибка']]
    };
    if (exact[path]) return exact[path];
    if (/^\/history\/[^/]+$/.test(path)) return [['S3', '/tasks'], ['История', '/history'], ['Детали запуска']];
    if (/^\/clickhouse\/history\/[^/]+$/.test(path)) return [['ClickHouse', '/clickhouse'], ['Тесты и история', '/clickhouse'], ['Результат теста']];
    if (/^\/clickhouse\/replicated-tests\/history\/[^/]+$/.test(path)) {
      return [['ClickHouse', '/clickhouse'], ['Replicated tests', '/clickhouse/replicated-tests'], ['Результат теста']];
    }
    return [];
  }

  function breadcrumbs() {
    if (location.pathname === '/' || document.querySelector('.breadcrumbs')) return;
    const items = breadcrumbDefinition(location.pathname);
    if (!items.length) return;
    const host = document.querySelector('main.container') || document.querySelector('main');
    const navs = host?.querySelectorAll(':scope > .corporate-nav');
    const anchor = navs?.length ? navs[navs.length - 1] : host?.querySelector(':scope > header');
    if (!host || !anchor) return;

    const element = document.createElement('nav');
    element.className = 'breadcrumbs';
    element.setAttribute('aria-label', 'Хлебные крошки');
    const home = document.createElement('a');
    home.href = '/';
    home.textContent = 'Главная';
    element.appendChild(home);
    items.forEach(([label, href], index) => {
      const separator = document.createElement('span');
      separator.className = 'breadcrumb-separator';
      separator.setAttribute('aria-hidden', 'true');
      separator.textContent = '›';
      element.appendChild(separator);
      const isLast = index === items.length - 1;
      const item = href && !isLast ? document.createElement('a') : document.createElement('span');
      if (item instanceof HTMLAnchorElement) item.href = href;
      item.textContent = label;
      if (isLast) item.setAttribute('aria-current', 'page');
      element.appendChild(item);
    });
    anchor.insertAdjacentElement('afterend', element);
  }

  function notify(message, type = 'info') {
    let host = document.querySelector('.toast-host');
    if (!host) {
      host = document.createElement('div');
      host.className = 'toast-host';
      host.setAttribute('aria-live', 'polite');
      host.setAttribute('aria-atomic', 'false');
      document.body.appendChild(host);
    }
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.setAttribute('role', type === 'error' ? 'alert' : 'status');
    toast.textContent = message;
    host.appendChild(toast);
    setTimeout(() => toast.classList.add('show'), 10);
    setTimeout(() => {
      toast.classList.remove('show');
      setTimeout(() => toast.remove(), 200);
    }, 4200);
  }

  function submitLogout(session) {
    const token = session?.csrfToken;
    const parameter = session?.csrfParameterName || '_csrf';
    if (!token) {
      notify('Не удалось получить CSRF-токен для выхода', 'error');
      return;
    }
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '/logout';
    form.hidden = true;
    const input = document.createElement('input');
    input.type = 'hidden';
    input.name = parameter;
    input.value = token;
    form.appendChild(input);
    document.body.appendChild(form);
    form.submit();
  }

  function applyRoleAwareUi(role, root = document) {
    const resolvedRole = role || 'NONE';
    activeRole = resolvedRole;
    document.documentElement.dataset.userRole = resolvedRole;
    const level = ROLE_LEVEL[resolvedRole] ?? 0;
    const elements = root.querySelectorAll ? [...root.querySelectorAll('[data-min-role]')] : [];
    if (root instanceof Element && root.matches('[data-min-role]')) elements.push(root);
    elements.forEach(element => {
      const required = ROLE_LEVEL[element.dataset.minRole] ?? 0;
      element.hidden = level < required;
    });
  }

  function observeRoleAwareUi() {
    new MutationObserver(mutations => {
      mutations.forEach(mutation => mutation.addedNodes.forEach(node => {
        if (node instanceof Element) applyRoleAwareUi(activeRole, node);
      }));
    }).observe(document.body, {childList: true, subtree: true});
  }

  function addSearchAndUser(session) {
    const header = document.querySelector('.brand-header');
    if (!header) return;
    const resolvedVersion = session?.version && !/^(null|undefined)$/i.test(String(session.version))
      ? String(session.version) : 'dev';
    document.querySelectorAll('.header-version').forEach(node => { node.textContent = `Версия ${resolvedVersion}`; });
    document.querySelectorAll('.footer-version').forEach(node => { node.textContent = `Версия ${resolvedVersion}`; });
    if (header.querySelector('.app-user-tools')) return;

    const roles = Array.isArray(session?.roles) ? session.roles.map(String) : [];
    const primary = roles.includes('ADMIN') ? 'ADMIN' : roles.includes('OPERATOR') ? 'OPERATOR' : roles.includes('VIEWER') ? 'VIEWER' : '';
    const username = String(session?.username || 'Пользователь');
    const tools = document.createElement('div');
    tools.className = 'app-user-tools';

    const search = document.createElement('form');
    search.className = 'global-search header-search';
    search.setAttribute('role', 'search');
    search.method = 'get';
    search.action = '/history';
    const searchInput = document.createElement('input');
    searchInput.type = 'search';
    searchInput.name = 'q';
    searchInput.setAttribute('aria-label', 'Поиск по истории S3');
    searchInput.placeholder = 'Найти запуск S3…';
    const searchButton = document.createElement('button');
    searchButton.type = 'submit';
    searchButton.textContent = 'Поиск';
    search.append(searchInput, searchButton);

    const menu = document.createElement('div');
    menu.className = 'user-menu';
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'user-menu-toggle';
    toggle.setAttribute('aria-haspopup', 'true');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-controls', 'app-user-menu');
    const usernameNode = document.createElement('span');
    usernameNode.textContent = username;
    toggle.appendChild(usernameNode);
    if (primary) {
      const roleNode = document.createElement('small');
      roleNode.textContent = primary;
      toggle.appendChild(roleNode);
    }

    const popover = document.createElement('div');
    popover.id = 'app-user-menu';
    popover.className = 'user-menu-popover';
    const identity = document.createElement('div');
    const strong = document.createElement('strong');
    strong.textContent = username;
    const roleList = document.createElement('span');
    roleList.textContent = roles.length ? roles.join(', ') : 'Без роли';
    identity.append(strong, roleList);
    popover.appendChild(identity);
    if (session?.securityEnabled) {
      const logout = document.createElement('button');
      logout.type = 'button';
      logout.className = 'logout-button';
      logout.textContent = 'Выйти';
      logout.addEventListener('click', () => submitLogout(session));
      popover.appendChild(logout);
    }
    const version = document.createElement('small');
    version.textContent = `Версия ${resolvedVersion}`;
    popover.appendChild(version);
    menu.append(toggle, popover);
    tools.append(search, menu);
    header.appendChild(tools);

    search.addEventListener('submit', event => {
      event.preventDefault();
      const query = searchInput.value.trim();
      if (query) location.href = `/history?q=${encodeURIComponent(query)}`;
    });
    const closeMenu = () => {
      menu.classList.remove('open');
      toggle.setAttribute('aria-expanded', 'false');
    };
    toggle.addEventListener('click', () => {
      const open = !menu.classList.contains('open');
      menu.classList.toggle('open', open);
      toggle.setAttribute('aria-expanded', String(open));
    });
    document.addEventListener('click', event => {
      if (!menu.contains(event.target)) closeMenu();
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && menu.classList.contains('open')) {
        closeMenu();
        toggle.focus();
      }
    });
    const effectiveRole = !session ? 'NONE' : session.securityEnabled ? (primary || 'NONE') : 'OPEN';
    applyRoleAwareUi(effectiveRole);
  }

  async function loadSession() {
    try {
      const response = await fetch('/api/session', {credentials: 'same-origin'});
      if (response.ok) {
        const session = await response.json();
        addSearchAndUser(session);
        return session;
      }
    } catch (_) {
      // The common shell remains usable when session metadata is temporarily unavailable.
    }
    addSearchAndUser(null);
    return null;
  }

  function historyBodies() {
    return [...document.querySelectorAll('tbody[data-history-page],tbody#history,tbody#history-body,tbody#m2History')]
      .filter(body => {
        const section = body.closest('section');
        return !section?.querySelector(':scope > .pager');
      });
  }

  function paginateHistory(body) {
    const rows = [...body.rows];
    const signature = rows.map(row => row.dataset.id || row.textContent).join('|');
    let state = body._historyPager;
    if (!state) {
      const wrap = body.closest('.table-wrap');
      if (!wrap) return;
      const pager = document.createElement('div');
      pager.className = 'pager history-pager';
      pager.setAttribute('aria-label', 'Постраничная навигация');
      pager.innerHTML = '<button type="button" class="secondary history-prev">← Назад</button><span class="history-page-info" aria-live="polite"></span><button type="button" class="secondary history-next">Вперёд →</button>';
      wrap.insertAdjacentElement('afterend', pager);
      state = body._historyPager = {page: 0, signature: '', pager};
      pager.querySelector('.history-prev').addEventListener('click', () => {
        state.page = Math.max(0, state.page - 1);
        paginateHistory(body);
      });
      pager.querySelector('.history-next').addEventListener('click', () => {
        state.page += 1;
        paginateHistory(body);
      });
    }
    if (state.signature !== signature) {
      state.signature = signature;
      state.page = 0;
    }
    const pages = Math.max(1, Math.ceil(rows.length / 10));
    state.page = Math.min(state.page, pages - 1);
    rows.forEach(row => row.classList.add('history-row-hidden'));
    rows.slice(state.page * 10, state.page * 10 + 10).forEach(row => row.classList.remove('history-row-hidden'));
    state.pager.querySelector('.history-page-info').textContent = `Страница ${state.page + 1} из ${pages} · записей: ${rows.length}`;
    state.pager.querySelector('.history-prev').disabled = state.page === 0;
    state.pager.querySelector('.history-next').disabled = state.page + 1 >= pages;
    state.pager.hidden = rows.length <= 10;
  }

  function enableHistoryPagination() {
    let scheduled = false;
    const refresh = () => {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(() => {
        scheduled = false;
        historyBodies().forEach(paginateHistory);
      });
    };
    refresh();
    new MutationObserver(refresh).observe(document.body, {childList: true, subtree: true});
    return refresh;
  }

  function enhanceTables() {
    document.querySelectorAll('table').forEach((table, index) => {
      table.querySelectorAll('thead th').forEach(header => header.setAttribute('scope', 'col'));
      if (table.querySelector('caption')) return;
      const caption = document.createElement('caption');
      caption.className = 'sr-only';
      const section = table.closest('section, article');
      caption.textContent = section?.querySelector('h2, h3')?.textContent?.trim() || `Таблица данных ${index + 1}`;
      table.prepend(caption);
    });
  }

  async function init() {
    ensureMetadata();
    breadcrumbs();
    enhanceTables();
    observeRoleAwareUi();
    const refreshHistoryPagination = enableHistoryPagination();
    window.EvoUI.refreshHistoryPagination = refreshHistoryPagination;
    await loadSession();
  }

  window.EvoUI = {notify, refreshHistoryPagination: () => {}};
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
