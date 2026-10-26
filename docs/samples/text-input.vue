<manifest>
{
  "format": 1,
  "id": "example:text_input",
  "version": "1.0.0",
  "name": "输入框示例",
  "author": "mcphone",
  "description": "输入文字并实时回显，切换禁用和条件显示。"
}
</manifest>
<template>
  <column id="root">
    <text>单行文字输入</text>
    <text-input id="draft" bind="draft" placeholder="点这里输入中文或英文" max-length="32" :enabled="editable" v-if="shown" />
    <text>当前内容：{{ draft }}</text>
    <toggle bind="editable" label="允许编辑" />
    <toggle bind="shown" label="显示输入框" />
    <button @click="draft = ''">清空</button>
    <button @click="draft = '你好，MCPhone'">设置示例文字</button>
  </column>
</template>
<script>
  state = { draft: '', editable: true, shown: true }
</script>
<style>
  #root { height: fill; padding: 4; gap: 5; background: $screen; }
</style>
