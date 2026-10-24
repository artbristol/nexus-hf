"""Minimal client for the Nexus REST API, for setting up and inspecting test repositories."""

import base64
import json
import urllib.error
import urllib.request


class Nexus:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        self.user = user
        self.password = password
        self._auth = "Basic " + base64.b64encode(("%s:%s" % (user, password)).encode()).decode()

    def call(self, method, path, body=None, content_type="application/json"):
        data = None
        if body is not None:
            data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, data=data, method=method)
        request.add_header("Authorization", self._auth)
        if data is not None:
            request.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
                return json.loads(payload) if payload and response.headers.get_content_type() == "application/json" \
                    else None
        except urllib.error.HTTPError as e:
            raise AssertionError("%s %s -> %d %s" % (method, path, e.code, e.read()[:300])) from None

    def create_proxy(self, name, remote_url, metadata_max_age=1440, auto_block=False, timeout=None, blocked=False):
        http_client = {"blocked": blocked, "autoBlock": auto_block}
        if timeout is not None:
            # retries: 0 so that a failure costs the Hub one request, not several
            http_client["connection"] = {"timeout": timeout, "retries": 0}
        self.call("POST", "/service/rest/v1/repositories/huggingface/proxy", self._proxy_body(
            name, remote_url, metadata_max_age, http_client))

    def update_proxy(self, name, remote_url, metadata_max_age=1440, auto_block=False, blocked=False):
        self.call("PUT", "/service/rest/v1/repositories/huggingface/proxy/" + name, self._proxy_body(
            name, remote_url, metadata_max_age, {"blocked": blocked, "autoBlock": auto_block}))

    @staticmethod
    def _proxy_body(name, remote_url, metadata_max_age, http_client):
        return {
            "name": name,
            "online": True,
            "storage": {"blobStoreName": "default", "strictContentTypeValidation": True},
            "proxy": {"remoteUrl": remote_url, "contentMaxAge": 1440, "metadataMaxAge": metadata_max_age},
            "negativeCache": {"enabled": True, "timeToLive": 1440},
            "httpClient": http_client,
        }

    def delete_repository(self, name):
        self.call("DELETE", "/service/rest/v1/repositories/" + name)

    def assets(self, repository):
        items, token = [], None
        while True:
            query = "?repository=" + repository + ("&continuationToken=" + token if token else "")
            page = self.call("GET", "/service/rest/v1/assets" + query)
            items += page["items"]
            token = page.get("continuationToken")
            if not token:
                return items

    def components(self, repository):
        page = self.call("GET", "/service/rest/v1/components?repository=" + repository)
        return page["items"]

    def anonymous_access(self, enabled):
        self.call("PUT", "/service/rest/v1/security/anonymous",
                  {"enabled": enabled, "userId": "anonymous", "realmName": "NexusAuthorizingRealm"})

    def is_anonymous_access_enabled(self):
        return self.call("GET", "/service/rest/v1/security/anonymous")["enabled"]
