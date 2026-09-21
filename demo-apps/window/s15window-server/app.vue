<template>
  <column>
    <column v-if="!backend.available" class="unavailable">
      <text>这个 App 需要「{{ backend.serverName }}」才能使用</text>
      <text class="hint">当前服务器没有安装它的后端</text>
    </column>
    <column v-else>
      <text class="title">窗口测试 App</text>
      <text>可用动作：{{ backend.actions.length }}</text>
      <button @click="call('daily')">领取 3 钻石（item.give）</button>
      <button @click="call('loot')">掷礼包表（loot.roll）</button>
      <button @click="call('buff')">加属性 + 速度（attr/effect）</button>
      <button @click="call('unbuff')">撤属性（只撤自己那条）</button>
      <button @click="call('points')">积分 +1（score）</button>
      <button @click="call('vip')">查会员（predicate）</button>
      <button @click="call('wallet')">查钱包（currency 只读）</button>
      <text class="hint">结果看底部 toast；原始输出看服务端日志</text>
    </column>
  </column>
</template>

<script>
  state = { }
</script>

<style>
  column { padding: 6; gap: 4; }
  button { width: fill; }
</style>
