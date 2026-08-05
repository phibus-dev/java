(() => {
  const originalFetch = window.fetch.bind(window);
  const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

  function meta(name) {
    return document.querySelector(`meta[name="${name}"]`)?.content || '';
  }

  window.fetch = (input, init = {}) => {
    const request = input instanceof Request ? input : null;
    const method = String(init.method || request?.method || 'GET').toUpperCase();
    const url = new URL(request?.url || String(input), window.location.href);
    const sameOrigin = url.origin === window.location.origin;

    if (!sameOrigin || !unsafeMethods.has(method)) {
      return originalFetch(input, init);
    }

    const token = meta('_csrf');
    const headerName = meta('_csrf_header');
    const headers = new Headers(request?.headers || undefined);
    new Headers(init.headers || undefined).forEach((value, name) => headers.set(name, value));

    if (token && headerName && !headers.has(headerName)) {
      headers.set(headerName, token);
    }

    return originalFetch(input, {
      ...init,
      method,
      headers,
      credentials: init.credentials || request?.credentials || 'same-origin'
    });
  };
})();
