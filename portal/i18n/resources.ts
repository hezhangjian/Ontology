type LocaleResource<T> = {
  [Key in keyof T]: T[Key] extends string ? string : LocaleResource<T[Key]>;
};

export const enUS = {
  language: {
    english: 'EN',
    switcherLabel: 'Language',
    zhCNShort: '中',
  },
  navigation: {
    collapseSidebar: 'Collapse sidebar',
    data: 'Data Access',
    dataConnections: 'Data Sources',
    datasets: 'Datasets',
    expandSidebar: 'Expand sidebar',
    pipelines: 'Data Pipelines',
    primary: 'Primary navigation',
  },
  ontology: {
    cancel: 'Cancel',
    closeCreate: 'Close create ontology dialog',
    create: 'Create',
    createHelp: 'Create an ontology with its own id, name, and description.',
    description: 'description',
    descriptionPlaceholder: 'Describe the business scope of this ontology.',
    id: 'id',
    idPlaceholder: 'supply-chain',
    idRequired: 'Enter an id.',
    name: 'name',
    namePlaceholder: 'Supply Chain',
    nameRequired: 'Enter a name.',
    new: 'New Ontology',
    switcherLabel: 'Switch ontology',
    unselected: 'Select Ontology',
  },
};

export const zhCN: LocaleResource<typeof enUS> = {
  language: {
    english: 'EN',
    switcherLabel: '语言',
    zhCNShort: '中',
  },
  navigation: {
    collapseSidebar: '收起侧边栏',
    data: '数据接入',
    dataConnections: '数据源',
    datasets: '数据集',
    expandSidebar: '展开侧边栏',
    pipelines: '数据管道',
    primary: '主导航',
  },
  ontology: {
    cancel: '取消',
    closeCreate: '关闭新建本体弹窗',
    create: '创建',
    createHelp: '创建一个包含 id、name 和描述的本体。',
    description: '描述',
    descriptionPlaceholder: '说明这个本体覆盖的业务范围。',
    id: 'id',
    idPlaceholder: 'supply-chain',
    idRequired: '请输入 id。',
    name: 'name',
    namePlaceholder: '供应链',
    nameRequired: '请输入 name。',
    new: '新建本体',
    switcherLabel: '切换本体',
    unselected: '选择本体',
  },
};

export const resources = {
  'en-US': {
    translation: enUS,
  },
  'zh-CN': {
    translation: zhCN,
  },
};
