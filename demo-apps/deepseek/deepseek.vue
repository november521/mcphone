<manifest>
{"format":1,"id":"mcpapps:deepseek","version":"0.1.0","name":"DeepSeek","author":"MCPhone","description":"单次文本问答，客户端直接请求 DeepSeek；API Key 由玩家自行配置。","icon":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABQAAAAUCAYAAACNiR0NAAAAAXNSR0IArs4c6QAAAZxJREFUOI2tlbFLw0AUxr/E/gM6ugkKJwgOXfwLhG5B7HYQCNkKFbLo1KGTU0uEbLFQ6FYo2QpOxc2hgyAoaHHqIqVCO9b2ueTO6yWNQXwQCO9973ffuxwXA0r4jBP+EBcvHUO8G5tA1V6QCbk5q6SCTR1QLlm5nQmturjpM066G8d1Ux2o7hzXXctVewF8xsnUk91+hPl4IhvTYAAwH0/Q7UeJurmpsVyyEk6FM3VbxHRCZ/iMk+O6aIVhovhbqHrBKAj7uptKu5EJC2xP6h3XldtUEIJWGOYC6QsGtodWGMptMMQZVEGn1hXuoutMoK4JbA9A/FHyusrjuAAAW29TAMByfwcAEu4a9ToAwKvVZE7ViH4JVAsCqoYK0kOFAfHIdLIrE4Htyf3ICl0nGT7jNDhv0mo0I59xoumCaLogn3H6evhIfXTdajSjwXmTfMZJjnx/eQsAWL5+/oxzsL3RoaoTvUB8bI6PijLx+DQEAFg2zxw5ancAAHqvvA/Vwl7xMBMm4n34vAZbuw+Fs7wwVSt6gfjGFvEfv4BvuLHqlZ47qikAAAAASUVORK5CYII="}
</manifest>
<template>
<column class="page">
  <row class="toolbar">
    <text class="title">DeepSeek</text>
    <button id="settings" v-if="!settings" :enabled="!loading" @click="openSettings()">设置</button>
  </row>
  <column v-if="!settings" class="content">
    <text id="status" :text="status" />
    <scroll id="answer" class="answer"><text :text="answer" /></scroll>
    <textarea id="question" class="question" bind="draft" :enabled="!loading" placeholder="输入问题（每次独立提问）" max-length="1024" />
    <row class="actions">
      <button id="send" class="action" :enabled="!loading && configured" @click="send()">发送</button>
      <button id="cancel" class="action" :enabled="loading" @click="cancelRequest()">取消等待</button>
    </row>
  </column>
  <column v-else class="content">
    <text>API Key · 仅本机保存</text>
    <textarea id="api-key" class="key" bind="keyDraft" placeholder="粘贴 API Key，保存后清空输入框" max-length="256" />
    <text id="key-status" :text="keyStatus" />
    <row class="actions">
      <button id="save-key" class="action" @click="saveKey()">保存</button>
      <button id="delete-key" class="action" :enabled="configured" @click="deleteKey()">删除密钥</button>
    </row>
    <text>使用你的 DeepSeek API 账户。</text>
    <spacer class="space" />
    <button id="back" @click="closeSettings()">返回问答</button>
  </column>
</column>
</template>
<script>
state = {
  draft: '', answer: '回答会显示在这里。', status: '先在设置中保存你的 API Key。',
  settings: false, keyDraft: '', keyStatus: '', configured: false,
  loading: false, requestId: 0
}

function validKey(value) {
  if (!value || value.length > 256) return false
  for (var i = 0; i < value.length; i++) {
    var code = value.charCodeAt(i)
    if (code < 33 || code > 126) return false
  }
  return true
}

function onInit() {
  state.configured = validKey(ctx.store.getString('apiKey', ''))
  state.status = state.configured ? '准备就绪。每次发送独立提问。' : '先在设置中保存你的 API Key。'
}

function openSettings() {
  if (state.loading) return
  state.keyDraft = ''
  state.configured = validKey(ctx.store.getString('apiKey', ''))
  state.keyStatus = state.configured ? '已保存密钥；输入新密钥可替换。' : '尚未保存密钥。'
  state.settings = true
}

function closeSettings() {
  state.keyDraft = ''
  state.settings = false
  state.status = state.configured ? '准备就绪。每次发送独立提问。' : '先在设置中保存你的 API Key。'
}

function saveKey() {
  var key = state.keyDraft.trim()
  if (!validKey(key)) {
    state.keyStatus = '请输入完整密钥，不要包含空格、换行或中文。'
    return
  }
  ctx.store.setString('apiKey', key)
  state.keyDraft = ''
  state.configured = true
  state.keyStatus = '密钥已保存在本机。'
}

function deleteKey() {
  ctx.store.remove('apiKey')
  state.keyDraft = ''
  state.configured = false
  state.keyStatus = '已删除密钥。'
}

function send() {
  if (state.loading || state.settings) return
  var question = state.draft.trim()
  if (!question) { state.status = '请先输入问题。'; return }
  var key = ctx.store.getString('apiKey', '')
  if (!validKey(key)) {
    state.configured = false
    state.status = '请先在设置中保存有效的 API Key。'
    return
  }
  state.requestId = ctx.http.request({
    url: 'https://api.deepseek.com/chat/completions', method: 'POST',
    headers: { 'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: 'deepseek-flash', stream: false, thinking: { type: 'disabled' }, max_tokens: 768,
      messages: [
        { role: 'system', content: '请简洁回答，使用纯文本，避免不必要的格式装饰。' },
        { role: 'user', content: question }
      ]
    }),
    timeoutMs: 90000
  }, 'received')
  state.loading = true
  state.answer = ''
  state.status = '正在等待回答，可取消等待。'
}

function cancelRequest() {
  if (!state.loading) return
  ctx.http.cancel(state.requestId)
  state.requestId = 0
  state.loading = false
  state.status = '已取消等待，可以重新发送。'
}

function failure(status, error) {
  if (status === 401) return '密钥无效，请在设置中检查。'
  if (status === 402) return 'API 账户余额不足，请检查 DeepSeek 账户。'
  if (status === 429) return '请求受限，请稍后手动重试。'
  if (status >= 500) return 'DeepSeek 服务暂不可用，请稍后重试。'
  if (error === 'TIMEOUT') return '请求超时，可以重新发送。'
  if (error === 'RESPONSE_TOO_LARGE') return '回复超过接收上限，请缩短问题后重试。'
  if (error === 'BUSY') return '当前网络请求较多，请稍后重试。'
  if (error === 'INVALID_UTF8') return '回复编码异常，请重试。'
  return status ? '请求未成功，HTTP ' + status + '。' : '连接失败，请检查网络后重试。'
}

function clipped(text) {
  var result = text.slice(0, 4096)
  var tail = result.charCodeAt(result.length - 1)
  return tail >= 55296 && tail <= 56319 ? result.slice(0, -1) : result
}

function received(status, body, error) {
  state.requestId = 0
  state.loading = false
  if (error || status < 200 || status >= 300) {
    state.status = failure(status, error)
    return
  }
  try {
    var data = JSON.parse(body)
    var choice = data && Array.isArray(data.choices) && data.choices.length ? data.choices[0] : null
    var message = choice && choice.message
    if (!message || typeof message.content !== 'string' || !message.content.trim()) {
      state.status = '没有收到文字回答，请重试。'
      return
    }
    state.answer = clipped(message.content)
    if (message.content.length > 4096) state.status = '回答较长，已截短显示。'
    else if (choice.finish_reason === 'length') state.status = '达到本次回复长度上限，可精简问题再问。'
    else state.status = '回答已收到。每次发送独立提问。'
  } catch (e) {
    state.status = '回复格式异常，请重新发送。'
  }
}
</script>
<style>
.page { width: fill; height: fill; gap: 3; padding: 4; }
.toolbar { width: fill; gap: 4; }
.title { width: 0; grow: 1; }
.content { width: fill; height: 0; grow: 1; gap: 3; }
.answer { height: 0; grow: 1; }
.question { height: 38; }
.key { height: 32; }
.actions { width: fill; gap: 4; }
.action { width: 0; grow: 1; }
.space { height: 0; grow: 1; }
</style>
