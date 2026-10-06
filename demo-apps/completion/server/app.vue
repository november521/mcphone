<script>
state={output:'',note:'',held:'',buyer:'',url:'https://example.com/'}
function run(action) {
    phone.call(action,{note:state.note,held:state.held,buyer:state.buyer,url:state.url},function(r){
        state.output=JSON.stringify(r).slice(0,2048);
        if(r.ok&&r.data.held)state.held=r.data.held;
        toast(r.ok?'服务端已返回，请核对实物和账目':r.message);
    });
}
function burst() {run('wallet');run('wallet');run('wallet');run('wallet');run('wallet');}
</script>
<template>
<scroll>
<column class="page">
  <text>后端验收：仅在测试世界操作</text>
  <text>结果为 UNKNOWN 时停止并核对</text>
  <text-input bind="output" max-length="4096" placeholder="完整返回结果"/>
  <button text="读身份、位置与周期" @click="run('read')"/>
  <button text="读钱包" @click="run('wallet')"/>
  <button text="同时发五个只读请求" @click="burst()"/>
  <button text="一次性领取一颗钻石" @click="run('claim')"/>
  <button text="掷表并给物品" @click="run('loot')"/>
  <button text="把第一个快捷栏物品放入收件箱" @click="run('mail')"/>
  <text-input bind="note" max-length="4096" placeholder="KV 内容，服务端值最多 2 KiB"/>
  <button text="保存并读取 KV" @click="run('kv')"/>
  <button text="HIGH 通知" @click="run('notify')"/>
  <button text="批准后增加十单位货币" @click="run('mint')"/>
  <button text="批准后销毁一单位货币" @click="run('burn')"/>
  <button text="托管一单位货币给自己" @click="run('hold')"/>
  <text-input bind="held" max-length="64" placeholder="托管 UUID"/>
  <button text="退回这一笔" @click="run('refund')"/>
  <button text="放款这一笔" @click="run('release')"/>
  <text-input bind="buyer" max-length="36" placeholder="指定买家 UUID"/>
  <button text="提出物品托管（需另行原生确认）" @click="run('offer')"/>
  <button text="列出当前真实资源适配器" @click="run('resource')"/>
  <text-input bind="url" max-length="512" placeholder="公开 HTTPS 地址"/>
  <button text="服务端 fetch（默认关闭）" @click="run('fetch')"/>
</column>
</scroll>
</template>
<style>
.page{padding:6;gap:4;}
</style>
