<manifest>
{"format":1,"id":"example:http_json","version":"1.1.0","name":"网络问答演示","author":"MCPhone"}
</manifest>
<template>
<column class="page">
  <text>网络问答演示</text>
  <text-input bind="url" :enabled="!loading" placeholder="HTTP / HTTPS 接口" max-length="64" />
  <text-input bind="question" :enabled="!loading" placeholder="输入问题" max-length="64" />
  <button id="send" :enabled="!loading" @click="send()">{{ loading ? '等待回答…' : '发送' }}</button>
  <button id="cancel" v-if="loading" @click="cancelRequest()">取消请求</button>
  <scroll id="reply" class="answer"><text :text="answer" /></scroll>
</column>
</template>
<script>
state = { url: 'http://127.0.0.1:18765/answer', question: '你好', answer: '先启动示例接口，再发送问题。', loading: false, requestId: 0 }

function send() {
  state.loading = true
  state.answer = '正在等待接口返回…'
  state.requestId = ctx.http.request({ url: state.url, method: 'POST', body: JSON.stringify({ question: state.question }), timeoutMs: 10000 }, 'received')
}

function cancelRequest() {
  ctx.http.cancel(state.requestId)
  state.requestId = 0
  state.loading = false
  state.answer = '已取消，可以重新发送。'
}

function received(status, body, error) {
  state.requestId = 0
  state.loading = false
  if (error) {
    state.answer = '请求未完成：' + error + (status ? '，HTTP ' + status : '')
    return
  }
  try {
    var data = JSON.parse(body)
    state.answer = typeof data.answer === 'string' ? data.answer.slice(0, 4096) : '接口没有返回 answer 字符串。'
  } catch (e) {
    state.answer = '接口返回的内容不是有效 JSON。'
  }
}
</script>
<style>
.page { gap: 4; padding: 4; height: fill; }
.answer { height: 0; grow: 1; }
</style>
