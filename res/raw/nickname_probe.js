// 尽力从已登录的 RC 页面里读出当前账号昵称。只读页面数据，不做任何修改。
// 返回 JSON 字符串：{ "nickname": "", "source": "", "title": "", "keys": [...] }
(function () {
    var result = { nickname: "", source: "", title: "", keys: [] };
    var NICK_KEYS = [
        "nickname", "nickName", "nick_name",
        "displayName", "display_name",
        "userName", "user_name"
    ];
    // 形如用户对象的记录才认 name 字段，避免把设备/工作区名字当成昵称。
    var USER_HINTS = ["email", "avatar", "avatarUrl", "mobile", "phone", "unionId", "userId"];

    function clean(value) {
        if (typeof value !== "string") {
            return "";
        }
        var text = value.replace(/\s+/g, " ").trim();
        return text.length > 0 && text.length <= 40 ? text : "";
    }

    function fromObject(node, depth) {
        if (!node || typeof node !== "object" || depth > 2) {
            return "";
        }
        for (var i = 0; i < NICK_KEYS.length; i++) {
            if (NICK_KEYS[i] in node) {
                var direct = clean(node[NICK_KEYS[i]]);
                if (direct) {
                    return direct;
                }
            }
        }
        var looksLikeUser = false;
        for (var h = 0; h < USER_HINTS.length; h++) {
            if (USER_HINTS[h] in node) {
                looksLikeUser = true;
                break;
            }
        }
        if (looksLikeUser && "name" in node) {
            var named = clean(node["name"]);
            if (named) {
                return named;
            }
        }
        for (var key in node) {
            if (!Object.prototype.hasOwnProperty.call(node, key)) {
                continue;
            }
            var child = node[key];
            if (child && typeof child === "object") {
                var nested = fromObject(child, depth + 1);
                if (nested) {
                    return nested;
                }
            }
        }
        return "";
    }

    try {
        result.title = typeof document.title === "string" ? document.title : "";
    } catch (e) {
    }

    // 1) localStorage / sessionStorage 里的 JSON 记录
    var stores = [["local", window.localStorage], ["session", window.sessionStorage]];
    for (var s = 0; s < stores.length && !result.nickname; s++) {
        var label = stores[s][0];
        var store = stores[s][1];
        if (!store) {
            continue;
        }
        for (var i = 0; i < store.length; i++) {
            var key = "";
            try {
                key = store.key(i);
            } catch (e) {
                continue;
            }
            if (!key) {
                continue;
            }
            if (result.keys.length < 60) {
                result.keys.push(label + ":" + key);
            }
            var raw = "";
            try {
                raw = store.getItem(key) || "";
            } catch (e) {
                continue;
            }
            if (raw.charAt(0) !== "{" && raw.charAt(0) !== "[") {
                continue;
            }
            var parsed = null;
            try {
                parsed = JSON.parse(raw);
            } catch (e) {
                continue;
            }
            var hit = fromObject(parsed, 0);
            if (hit) {
                result.nickname = hit;
                result.source = label + ":" + key;
            }
        }
    }

    // 2) 页面里的可见昵称节点
    if (!result.nickname) {
        var selectors = [
            '[class*="nickname" i]',
            '[class*="user-name" i]',
            '[class*="userName" i]',
            '[data-testid*="user" i]',
            'img[class*="avatar" i][alt]'
        ];
        for (var j = 0; j < selectors.length && !result.nickname; j++) {
            var nodes = [];
            try {
                nodes = document.querySelectorAll(selectors[j]);
            } catch (e) {
                continue;
            }
            for (var n = 0; n < nodes.length; n++) {
                var element = nodes[n];
                var text = clean(
                    element.getAttribute && element.getAttribute("alt")
                        ? element.getAttribute("alt")
                        : element.textContent);
                if (text) {
                    result.nickname = text;
                    result.source = "dom:" + selectors[j];
                }
            }
        }
    }

    return JSON.stringify(result);
})()
