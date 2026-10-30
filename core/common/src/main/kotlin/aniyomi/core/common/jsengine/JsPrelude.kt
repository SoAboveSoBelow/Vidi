package aniyomi.core.common.jsengine

/**
 * Evaluated before project code.
 *
 * Wraps the handle-based [DomBridge] in node objects so project code never sees
 * a handle, and installs the globals a project is written against. Kept to ES5
 * syntax and `var`, because this is evaluated as a script rather than a module -
 * which is also why a project assigns to a global `source` instead of using
 * `export`.
 *
 * `__entry` dereferences `source` lazily inside its methods, so the prelude can
 * be evaluated before the project script that defines it.
 */
internal val JS_PRELUDE = """
    var console = { log: function (message) { __log.log(String(message)); } };

    /* The manifest's base URL. Set by the host; a project should not reassign it. */
    var baseUrl = __source.baseUrl();

    function Node(handle) { this._h = handle; }

    Node.prototype.select = function (query) {
        var handles = __dom.select(this._h, query);
        var out = [];
        for (var i = 0; i < handles.length; i++) {
            out.push(new Node(handles[i]));
        }
        return out;
    };

    Node.prototype.selectFirst = function (query) {
        var handle = __dom.selectFirst(this._h, query);
        return handle === 0 ? null : new Node(handle);
    };

    Node.prototype.attr = function (name) { return __dom.attr(this._h, name); };
    Node.prototype.absAttr = function (name) { return __dom.absAttr(this._h, name); };
    Node.prototype.text = function () { return __dom.text(this._h); };
    Node.prototype.ownText = function () { return __dom.ownText(this._h); };
    Node.prototype.html = function () { return __dom.html(this._h); };

    /*
     * Takes the first attribute holding a real url, skipping base64 placeholders.
     * Built in so no project author has to rediscover that a lazy-loaded card
     * puts a 1x1 gif in src and the real url in data-rd-src.
     */
    Node.prototype.firstAttr = function () {
        for (var i = 0; i < arguments.length; i++) {
            var value = __dom.absAttr(this._h, arguments[i]);
            if (value && value.indexOf('data:') !== 0) {
                return value;
            }
        }
        return null;
    };

    /*
     * Select-and-read in one step, returning null when the node is absent.
     *
     * A real listing is not homogeneous: rule34video serves promo cards that
     * carry `item thumb` and `a.th` but no image at all, so `selectFirst(...)`
     * returns null and reading a property off it kills the whole page. Without
     * these, every project author writes that bug once.
     */
    Node.prototype.textIn = function (selector) {
        var node = this.selectFirst(selector);
        return node === null ? null : node.text();
    };

    Node.prototype.attrIn = function (selector) {
        var node = this.selectFirst(selector);
        if (node === null) return null;
        return node.firstAttr.apply(node, Array.prototype.slice.call(arguments, 1));
    };

    /* Plain attribute read, no url resolution - for meta content, ids, data-*. */
    Node.prototype.rawIn = function (selector, name) {
        var node = this.selectFirst(selector);
        return node === null ? null : node.attr(name);
    };

    function parse(html, baseUrl) {
        return new Node(__dom.parse(html, baseUrl || ''));
    }

    function __headerLines(headers) {
        if (!headers) return '';
        var lines = [];
        for (var name in headers) {
            if (Object.prototype.hasOwnProperty.call(headers, name)) {
                lines.push(name + ': ' + headers[name]);
            }
        }
        return lines.join('\n');
    }

    function Response(handle) { this._h = handle; }

    Response.prototype.status = function () { return __http.status(this._h); };
    Response.prototype.url = function () { return __http.finalUrl(this._h); };
    Response.prototype.header = function (name) { return __http.header(this._h, name); };
    Response.prototype.text = function () { return __http.bodyText(this._h); };

    /*
     * Parses host-side and hands back a node, so the page body never crosses
     * the boundary. Prefer this over text() whenever the body is HTML.
     */
    Response.prototype.html = function (baseUrl) {
        return new Node(__http.bodyDocument(this._h, baseUrl || ''));
    };

    var http = {
        get: function (url, headers) {
            return new Response(__http.request('GET', url, __headerLines(headers), ''));
        },
        post: function (url, body, headers) {
            return new Response(__http.request('POST', url, __headerLines(headers), body || ''));
        },
        delete: function (url, headers) {
            return new Response(__http.request('DELETE', url, __headerLines(headers), ''));
        },

        /*
         * Where a link ends up, without downloading what is there. Use this for
         * media URLs: a plain get() would follow the redirect and pull the whole
         * file into memory to tell you the same thing.
         */
        resolveUrl: function (url, headers) {
            return __http.resolveUrl(url, __headerLines(headers));
        },

        /*
         * The cookies this app would send to a URL, as a header value, for
         * passing to the player - which has no access to the cookie jar.
         */
        cookiesFor: function (url) { return __http.cookiesFor(url); }
    };

    /* Spike only: the host's pre-parsed document, to time extraction alone. */
    function pinnedDocument() {
        return new Node(__host.documentHandle());
    }

    /*
     * Compiles the project without running it.
     *
     * new Function() parses the whole body and throws a SyntaxError, which -
     * unlike the host's evaluate() - is a JavaScript object carrying whatever
     * location the engine knows. The wrapper it builds around the body may
     * shift the reported line, so everything the error holds is returned
     * verbatim and the host decides what it means rather than assuming.
     */
    var __check = {
        report: function (src) {
            var out = { ok: true };
            try {
                new Function(src);
            } catch (e) {
                out.ok = false;
                out.name = String(e && e.name ? e.name : '');
                out.message = String(e && e.message ? e.message : e);
                out.line = (e && typeof e.lineNumber === 'number') ? e.lineNumber : -1;
                out.column = (e && typeof e.columnNumber === 'number') ? e.columnNumber : -1;
                out.stack = String(e && e.stack ? e.stack : '');
                out.text = String(e);
            }
            return JSON.stringify(out);
        },

        compile: function () { return __check.report(__source.script()); },

        /* Same path as compile(), for measuring the wrapper's line offset. */
        probe: function (src) { return __check.report(src); }
    };

    var __entry = {
        call: function (name, argsJson) {
            if (typeof source === 'undefined' || source === null) {
                throw new Error('No global `source` object was defined');
            }
            if (typeof source[name] !== 'function') {
                /*
                 * Lists what the project did define. "source.details is not a
                 * function" is true and useless: it cannot distinguish a
                 * misspelled key from a truncated paste from a stale copy of
                 * the script, and those have completely different fixes.
                 */
                var defined = [];
                for (var key in source) {
                    if (typeof source[key] === 'function') defined.push(key);
                }
                throw new Error(
                    'source.' + name + ' is not a function. This project defines: ' +
                    (defined.length ? defined.join(', ') : '(nothing)')
                );
            }
            var args = argsJson ? JSON.parse(argsJson) : {};
            return JSON.stringify(source[name](args));
        }
    };
""".trimIndent()
