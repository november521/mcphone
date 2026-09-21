<template>
  <column class="page">
    <column v-if="!backend.available" class="unavailable">
      <text>这个 App 需要「{{ backend.serverName }}」才能使用</text>
      <text class="hint">当前服务器没有安装它的后端</text>
    </column>
    <column v-else>
      <text class="title">窗口测试 App</text>
      <text>可用动作：{{ backend.actions.length }}</text>
      <button class="btn" @click="call('daily')">领取 3 钻石（item.give）</button>
      <button class="btn" @click="call('loot')">掷礼包表（loot.roll）</button>
      <button class="btn" @click="call('buff')">加属性 + 速度（attr/effect）</button>
      <button class="btn" @click="call('unbuff')">撤属性（只撤自己那条）</button>
      <button class="btn" @click="call('points')">积分 +1（score）</button>
      <button class="btn" @click="call('vip')">查会员（predicate）</button>
      <button class="btn" @click="call('wallet')">查钱包（currency 只读）</button>
      <text class="hint">结果看底部 toast；原始输出看服务端日志</text>
    </column>
  </column>
</template>

<script>
  state = { }
</script>

<style>
  .page { padding: 6; gap: 4; }
  .btn { width: fill; }
</style>
