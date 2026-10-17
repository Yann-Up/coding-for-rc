// 远控网页用 Web Notifications API 发「回合完成 / 待回答 / 待审批」提醒，但 WebView 不实现这套 API：
// 页面里的 typeof Notification 守卫会判定「浏览器不支持」，于是整条发送路径被跳过、设置项显示被阻止。
// 这里补一份最小实现，把通知原样交给原生发系统通知；只覆盖页面真正用到的那几个接口。
(function () {
    var BRIDGE = window.AndroidNotify;
    if (!BRIDGE || window.__kimircNotifyShim) {
        // 注入可能早于桥对象可用，这种情况下不落标记，等下一次注入重试。
        return;
    }
    window.__kimircNotifyShim = true;

    var seq = 0;
    var pending = {};   // callbackId -> requestPermission 的 resolve
    var live = {};      // tag -> Notification 实例，用来把点击回灌给页面

    function fire(instance, type) {
        var event = { type: type, target: instance };
        var handlers = instance.__handlers[type] || [];
        for (var i = 0; i < handlers.length; i++) {
            try {
                handlers[i].call(instance, event);
            } catch (e) {
            }
        }
        var direct = instance["on" + type];
        if (typeof direct === "function") {
            try {
                direct.call(instance, event);
            } catch (e) {
            }
        }
    }

    // 原生权限结果回灌：resolve 页面那次 requestPermission 的 Promise。
    window.__kimircNotifyPermission = function (callbackId, state) {
        var resolve = pending[callbackId];
        if (!resolve) {
            return;
        }
        delete pending[callbackId];
        resolve(state);
    };

    // 用户点了系统通知：把 click 事件回灌给页面，页面自己在 onclick 里切换会话。
    window.__kimircNotifyClick = function (tag) {
        var instance = live[tag];
        if (instance) {
            fire(instance, "click");
        }
    };

    function Notification(title, options) {
        options = options || {};
        this.title = title == null ? "" : String(title);
        this.body = options.body == null ? "" : String(options.body);
        this.tag = options.tag == null ? "" : String(options.tag);
        this.silent = !!options.silent;
        this.data = options.data;
        this.onclick = null;
        this.onclose = null;
        this.onerror = null;
        this.onshow = null;
        this.__handlers = {};
        if (!this.tag) {
            this.tag = "kimirc-" + (++seq);
        }
        live[this.tag] = this;
        try {
            BRIDGE.show(this.tag, this.title, this.body, this.silent);
        } catch (e) {
        }
        var self = this;
        setTimeout(function () {
            fire(self, "show");
        }, 0);
    }

    Notification.prototype.close = function () {
        delete live[this.tag];
        try {
            BRIDGE.close(this.tag);
        } catch (e) {
        }
        fire(this, "close");
    };

    Notification.prototype.addEventListener = function (type, handler) {
        if (typeof handler !== "function") {
            return;
        }
        var handlers = this.__handlers[type] || (this.__handlers[type] = []);
        handlers.push(handler);
    };

    Notification.prototype.removeEventListener = function (type, handler) {
        var handlers = this.__handlers[type];
        if (!handlers) {
            return;
        }
        for (var i = handlers.length - 1; i >= 0; i--) {
            if (handlers[i] === handler) {
                handlers.splice(i, 1);
            }
        }
    };

    Notification.requestPermission = function (callback) {
        var promise = new Promise(function (resolve) {
            var id = "p" + (++seq);
            pending[id] = resolve;
            try {
                BRIDGE.requestPermission(id);
            } catch (e) {
                delete pending[id];
                resolve(Notification.permission);
            }
        });
        if (typeof callback === "function") {
            promise.then(callback);
        }
        return promise;
    };

    // 页面每次发送前都会重新读这个值，所以这里必须如实反映系统当前的授权状态：
    // 未授权时报 default，页面才会去请求权限；报 denied 会让它彻底放弃。
    Object.defineProperty(Notification, "permission", {
        get: function () {
            try {
                return BRIDGE.permission();
            } catch (e) {
                return "denied";
            }
        }
    });

    Notification.maxActions = 0;

    window.Notification = Notification;
})();
