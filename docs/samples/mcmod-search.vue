<manifest>
{"format":1,"id":"example:mcmod_search_probe","version":"0.2.0","name":"MC百科搜索","author":"MCPhone"}
</manifest>
<template>
<column class="page">
  <text>MC百科 · 模组搜索</text>
  <text-input id="query" bind="query" :enabled="!loading" placeholder="输入模组名" max-length="32" />
  <row class="actions">
    <button id="send" :enabled="!loading" @click="send()">搜索</button>
    <button id="cancel" v-if="loading" @click="cancelSearch()">取消</button>
  </row>
  <text :text="status" />
  <scroll id="results" class="results">
    <column>
      <column v-for="row in results" :key="row.id">
        <column v-if="row.visible" class="result">
          <text :text="row.title" />
          <button @click="openUrl(row.url)">查看原文</button>
          <text :text="row.summary" />
          <text :text="row.url" />
        </column>
      </column>
    </column>
  </scroll>
</column>
</template>
<script>
state = {
  query: '机械动力', loading: false, requestId: 0, lastSearch: 0, status: '点击搜索；两次查询至少间隔 10 秒。',
  results: [
    { id: 1, title: '', summary: '', url: '', visible: false },
    { id: 2, title: '', summary: '', url: '', visible: false },
    { id: 3, title: '', summary: '', url: '', visible: false },
    { id: 4, title: '', summary: '', url: '', visible: false },
    { id: 5, title: '', summary: '', url: '', visible: false }
  ]
}

function clearResults() {
  for (var i = 0; i < state.results.length; i++) {
    state.results[i].title = ''
    state.results[i].summary = ''
    state.results[i].url = ''
    state.results[i].visible = false
  }
}

function send() {
  var query = state.query.trim()
  if (!query) { state.status = '请先输入模组名称。'; return }
  var now = Math.floor(Date.now() / 1000)
  if (now - state.lastSearch < 10) { state.status = '请间隔 10 秒后再次搜索。'; return }
  clearResults()
  state.loading = true
  state.lastSearch = now
  state.status = '正在请求 MC 百科…'
  state.requestId = ctx.http.request({
    url: 'https://search.mcmod.cn/s?key=' + encodeURIComponent(query) + '&filter=1',
    timeoutMs: 20000
  }, 'received')
}

function cancelSearch() {
  ctx.http.cancel(state.requestId)
  state.requestId = 0
  state.loading = false
  state.status = '已取消，可以重新搜索。'
}

function plain(text, limit) {
  var result = '', pos = 0
  while (pos < text.length && result.length < limit) {
    var open = text.indexOf('<', pos)
    if (open < 0) { result += text.slice(pos, pos + limit - result.length); break }
    result += text.slice(pos, Math.min(open, pos + limit - result.length))
    var end = text.indexOf('>', open + 1)
    if (end < 0) break
    pos = end + 1
  }
  result = result.split('&nbsp;').join(' ').split('&amp;').join('&').split('&lt;').join('<').split('&gt;').join('>').split('&quot;').join('"').split('&#39;').join("'").trim().slice(0, limit)
  var tail = result.charCodeAt(result.length - 1)
  return tail >= 55296 && tail <= 56319 ? result.slice(0, -1) : result
}

function parseResults(body) {
  var pos = body.indexOf('<div class="search-result-list">'), count = 0
  if (pos < 0) return 0
  while (count < state.results.length) {
    var begin = body.indexOf('<div class="result-item">', pos)
    if (begin < 0) break
    var end = body.indexOf('<div class="result-item">', begin + 24)
    if (end < 0) end = body.length
    pos = end
    var item = body.slice(begin, Math.min(end, begin + 4096))
    var href = item.indexOf('href="https://www.mcmod.cn/class/')
    if (href < 0) continue
    var quote = item.indexOf('"', href + 6)
    var titleStart = item.indexOf('>', quote + 1)
    var titleEnd = item.indexOf('</a>', titleStart + 1)
    if (quote < 0 || titleStart < 0 || titleEnd < 0) continue
    var url = item.slice(href + 6, quote)
    var title = plain(item.slice(titleStart + 1, titleEnd), 120)
    if (!title || url.length > 256 || url.indexOf('.html') < 0) continue
    var summaryStart = item.indexOf('<div class="body">')
    var summaryEnd = item.indexOf('</div>', summaryStart + 18)
    state.results[count].title = title
    state.results[count].url = url
    state.results[count].summary = summaryStart < 0 || summaryEnd < 0 ? '' : plain(item.slice(summaryStart + 18, summaryEnd), 160)
    state.results[count].visible = true
    count++
  }
  return count
}

function received(status, body, error) {
  state.requestId = 0
  state.loading = false
  if (error) {
    state.status = error === 'RESPONSE_TOO_LARGE' ? '网页超过 64 KiB，现有脚本接口无法接收。' : '查询失败：' + error + (status ? '，HTTP ' + status : '')
    return
  }
  var count = parseResults(body)
  state.status = count ? '已显示前 ' + count + ' 条；查看原文将打开系统浏览器。' : '未提取到结果；请间隔 10 秒重试，也可能无匹配或网页结构已变化。'
}
</script>
<style>
.page { width: fill; height: fill; gap: 3; padding: 4; }
.actions { gap: 4; }
.results { height: 0; grow: 1; }
.result { gap: 2; padding: 2; }
</style>
