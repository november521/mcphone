<script>
state={note:'',url:'https://example.com/',token:'',output:'',image:''}
function save() {phone.sealed.put('completion',state.note,function(r){state.output=JSON.stringify(r);toast(r.message);});}
function load() {phone.sealed.get('completion',function(r){state.output=JSON.stringify(r).slice(0,2048);if(r.ok)state.note=r.data.text;toast(r.message);});}
function fetch() {phone.fetch(state.url,{authorization:state.token,offset:0},function(r){state.output=JSON.stringify(r).slice(0,2048);});}
function image() {phone.image(state.url,{authorization:state.token},function(r){state.output=JSON.stringify(r);if(r.status==='READY')state.image=r.src;});}
function clear() {state.note='';state.token='';state.image='';state.output='';}
</script>
<template>
<scroll>
<column class="page">
  <text>前端验收</text>
  <text-input bind="note" max-length="4096" placeholder="中文、表情、粘贴与保险箱内容"/>
  <button text="保存保险箱（先原生解锁）" @click="save()"/>
  <button text="读取保险箱" @click="load()"/>
  <text-input bind="url" max-length="512" placeholder="包声明的公开 HTTPS 地址"/>
  <text-input bind="token" max-length="512" placeholder="仅使用测试凭证，不在日志中填写"/>
  <button text="私人文本请求" @click="fetch()"/>
  <button text="私人 PNG 请求" @click="image()"/>
  <image :src="image"/>
  <text-input bind="output" max-length="4096" placeholder="返回状态"/>
  <button text="清空页面状态" @click="clear()"/>
</column>
</scroll>
</template>
<style>
.page{padding:6;gap:4;}
</style>
