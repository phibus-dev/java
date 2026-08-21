(() => {
  const KEY = 'evo-snt-ui-view';
  const MODES = [
    {id:'A', name:'Карточный', title:'Карточный (рекомендуемый)'},
    {id:'B', name:'Инфоблоки', title:'Информационные блоки'},
    {id:'C', name:'Компактный', title:'Компактный с иконками'},
    {id:'D', name:'Табличный', title:'Табличный фокус'}
  ];
  const ROLE_LEVEL = {VIEWER:1, OPERATOR:2, ADMIN:3};

  function currentMode() { const saved=localStorage.getItem(KEY); return MODES.some(m=>m.id===saved)?saved:'A'; }
  function applyMode(mode) {
    document.documentElement.dataset.uiView=mode; localStorage.setItem(KEY,mode);
    document.querySelectorAll('[data-ui-mode]').forEach(b=>{const s=b.dataset.uiMode===mode;b.classList.toggle('active',s);b.setAttribute('aria-pressed',s?'true':'false');});
    window.dispatchEvent(new Event('resize'));
  }
  function ensureMetadata() {
    if (!document.querySelector('link[rel="icon"]')) document.head.insertAdjacentHTML('beforeend','<link rel="icon" href="/favicon.svg" type="image/svg+xml">');
    if (!document.querySelector('link[rel="manifest"]')) document.head.insertAdjacentHTML('beforeend','<link rel="manifest" href="/manifest.webmanifest">');
    if (!document.title.includes('ЭВО.СНТ')) document.title = `${document.title || 'ЭВО.СНТ'} — ЭВО.СНТ`;
  }
  function makeSwitcher() {
    if (document.querySelector('.ui-view-switcher')) return;
    const host=document.querySelector('main.container')||document.querySelector('main')||document.body;
    const s=document.createElement('section'); s.className='ui-view-switcher'; s.setAttribute('aria-label','Вариант представления');
    s.innerHTML=`<span class="ui-view-label">Вариант представления:</span><div class="ui-view-buttons">${MODES.map(m=>`<button type="button" class="ui-view-button" data-ui-mode="${m.id}" title="${m.title}"><strong>${m.id}</strong><span>${m.name}</span></button>`).join('')}</div>`;
    const navs=host.querySelectorAll('.corporate-nav'), first=host.querySelector('.panel,.hero-panel');
    if(navs.length) navs[navs.length-1].insertAdjacentElement('afterend',s); else if(first) first.insertAdjacentElement('beforebegin',s); else host.prepend(s);
    s.addEventListener('click',e=>{const b=e.target.closest('[data-ui-mode]');if(b)applyMode(b.dataset.uiMode);}); applyMode(currentMode());
  }
  function csrf() {
    const metaToken=document.querySelector('meta[name="_csrf"]')?.content;
    const metaHeader=document.querySelector('meta[name="_csrf_header"]')?.content;
    const cookie=document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
    return {token:metaToken||(cookie?decodeURIComponent(cookie[1]):''), header:metaHeader||'X-XSRF-TOKEN'};
  }
  function breadcrumbs() {
    if(document.querySelector('.breadcrumbs')||location.pathname==='/') return;
    const labels={tasks:'S3',history:'История',clickhouse:'ClickHouse',agents:'Агенты','distributed-tests':'Распределённые тесты',monitoring:'Мониторинг',settings:'Настройки',schedules:'Расписания','replicated-tests':'Replicated tests','failover-tests':'Failover',replication:'Replication',ha:'HA Dashboard',keycloak:'Keycloak'};
    const parts=location.pathname.split('/').filter(Boolean); if(!parts.length)return;
    let path=''; const items=['<a href="/">Главная</a>'];
    parts.forEach((p,i)=>{path+=`/${p}`;const label=labels[p]||decodeURIComponent(p);items.push(i===parts.length-1?`<span>${label}</span>`:`<a href="${path}">${label}</a>`);});
    const el=document.createElement('nav');el.className='breadcrumbs';el.setAttribute('aria-label','Хлебные крошки');el.innerHTML=items.join('<b>›</b>');
    const host=document.querySelector('main.container')||document.querySelector('main');const nav=host?.querySelector('.corporate-nav'); if(nav)nav.insertAdjacentElement('afterend',el);
  }
  function notify(message,type='info') {
    let host=document.querySelector('.toast-host'); if(!host){host=document.createElement('div');host.className='toast-host';document.body.appendChild(host);}
    const t=document.createElement('div');t.className=`toast toast-${type}`;t.textContent=message;host.appendChild(t);setTimeout(()=>t.classList.add('show'),10);setTimeout(()=>{t.classList.remove('show');setTimeout(()=>t.remove(),200);},4200);
  }
  function addSearchAndUser(session) {
    const header=document.querySelector('.brand-header'); if(!header||header.querySelector('.app-user-tools')) return;
    const roles=session?.roles||[]; const primary=roles.includes('ADMIN')?'ADMIN':roles.includes('OPERATOR')?'OPERATOR':roles.includes('VIEWER')?'VIEWER':'';
    const box=document.createElement('div');box.className='app-user-tools';
    box.innerHTML=`<form class="global-search header-search"><input type="search" aria-label="Глобальный поиск" placeholder="Найти тест…"><button type="submit">Поиск</button></form><div class="user-menu"><button type="button" class="user-menu-toggle"><span>${session?.username||'Пользователь'}</span>${primary?`<small>${primary}</small>`:''}</button><div class="user-menu-popover"><div><strong>${session?.username||'Пользователь'}</strong><span>${roles.length?roles.join(', '):'Без роли'}</span></div>${session?.securityEnabled?'<button type="button" class="logout-button">Выйти</button>':''}<small>Версия ${session?.version||'dev'}</small></div></div>`;
    header.appendChild(box);
    const sf=box.querySelector('.global-search');sf.addEventListener('submit',e=>{e.preventDefault();const q=sf.querySelector('input').value.trim();if(q)location.href=`/history?search=${encodeURIComponent(q)}`;});
    box.querySelector('.user-menu-toggle').addEventListener('click',()=>box.querySelector('.user-menu').classList.toggle('open'));
    box.querySelector('.logout-button')?.addEventListener('click',async()=>{const c=csrf();const h={};if(c.token)h[c.header]=c.token;const r=await fetch('/logout',{method:'POST',headers:h,credentials:'same-origin'});if(r.ok||r.redirected)location.href='/';else notify('Не удалось завершить сессию','error');});
    applyRoleAwareUi(primary);
  }
  function applyRoleAwareUi(role) {
    document.documentElement.dataset.userRole=role||'OPEN'; const level=ROLE_LEVEL[role]||99;
    document.querySelectorAll('[data-min-role]').forEach(el=>{const need=ROLE_LEVEL[el.dataset.minRole]||0;el.hidden=level<need;});
    if(role==='VIEWER') document.querySelectorAll('form button[type="submit"],button[data-action="delete"],button[data-action="save"]').forEach(b=>{if(!/поиск|обнов|фильтр/i.test(b.textContent||'')){b.disabled=true;b.title='Недоступно для роли VIEWER';}});
    if(role==='OPERATOR'||role==='VIEWER') document.querySelectorAll('a[href^="/settings"]').forEach(a=>a.classList.add('role-restricted'));
  }
  async function loadSession() {
    try {const r=await fetch('/api/session',{credentials:'same-origin'});if(r.ok){const s=await r.json();addSearchAndUser(s);return s;}} catch(_){/* optional */}
    addSearchAndUser(null); return null;
  }
  function historySearch() {
    const q=new URLSearchParams(location.search).get('search');if(!q||!location.pathname.includes('history'))return;
    const needle=q.toLowerCase();document.querySelectorAll('tbody tr').forEach(tr=>tr.hidden=!tr.textContent.toLowerCase().includes(needle));
    notify(`Фильтр истории: ${q}`,'info');
  }
  function enhanceForms() {
    document.addEventListener('submit',e=>{if(e.defaultPrevented)return;const f=e.target;if(f.matches('form')&&!f.classList.contains('global-search'))setTimeout(()=>notify('Операция отправлена','info'),50);},true);
  }
  async function init(){ensureMetadata();breadcrumbs();makeSwitcher();await loadSession();historySearch();enhanceForms();}
  window.EvoUI={notify,applyMode};document.documentElement.dataset.uiView=currentMode();
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init);else init();
})();
