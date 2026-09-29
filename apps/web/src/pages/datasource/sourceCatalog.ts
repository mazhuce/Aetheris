export type DataSourceCategory = '关系型数据库' | '分布式数据库' | '数仓与湖仓' | '企业与联邦查询'

export interface DataSourceDefinition {
  value: string
  label: string
  category: DataSourceCategory
  defaultPort: number
  defaultDatabase: string
  description: string
}

export const dataSourceCatalog: DataSourceDefinition[] = [
  { value: 'postgresql', label: 'PostgreSQL', category: '关系型数据库', defaultPort: 5432, defaultDatabase: 'postgres', description: '开源企业级关系型数据库' },
  { value: 'mysql', label: 'MySQL', category: '关系型数据库', defaultPort: 3306, defaultDatabase: 'mysql', description: '广泛使用的关系型数据库' },
  { value: 'mariadb', label: 'MariaDB', category: '关系型数据库', defaultPort: 3306, defaultDatabase: 'mysql', description: '兼容 MySQL 协议' },
  { value: 'greenplum', label: 'Greenplum', category: '分布式数据库', defaultPort: 5432, defaultDatabase: 'postgres', description: 'PostgreSQL 协议分布式数仓' },
  { value: 'opengauss', label: 'openGauss', category: '分布式数据库', defaultPort: 5432, defaultDatabase: 'postgres', description: 'PostgreSQL 协议企业数据库' },
  { value: 'cockroachdb', label: 'CockroachDB', category: '分布式数据库', defaultPort: 26257, defaultDatabase: 'defaultdb', description: 'PostgreSQL 协议云原生数据库' },
  { value: 'yugabytedb', label: 'YugabyteDB', category: '分布式数据库', defaultPort: 5433, defaultDatabase: 'yugabyte', description: 'PostgreSQL 协议分布式数据库' },
  { value: 'tidb', label: 'TiDB', category: '分布式数据库', defaultPort: 4000, defaultDatabase: 'test', description: 'MySQL 协议分布式数据库' },
  { value: 'oceanbase', label: 'OceanBase', category: '分布式数据库', defaultPort: 2881, defaultDatabase: 'test', description: 'MySQL 模式分布式数据库' },
  { value: 'doris', label: 'Apache Doris', category: '数仓与湖仓', defaultPort: 9030, defaultDatabase: 'information_schema', description: 'MySQL 协议实时分析数仓' },
  { value: 'starrocks', label: 'StarRocks', category: '数仓与湖仓', defaultPort: 9030, defaultDatabase: 'information_schema', description: 'MySQL 协议实时分析数据库' },
  { value: 'hive2', label: 'Apache Hive', category: '数仓与湖仓', defaultPort: 10000, defaultDatabase: 'default', description: '通过 HiveServer2 JDBC 接入' },
  { value: 'spark', label: 'Spark Thrift Server', category: '数仓与湖仓', defaultPort: 10000, defaultDatabase: 'default', description: '兼容 Hive2 JDBC 协议' },
  { value: 'sqlserver', label: 'Microsoft SQL Server', category: '关系型数据库', defaultPort: 1433, defaultDatabase: 'master', description: 'Microsoft 企业级关系型数据库' },
  { value: 'oracle', label: 'Oracle Database', category: '关系型数据库', defaultPort: 1521, defaultDatabase: 'ORCL', description: 'Oracle 企业级关系型数据库' },
  { value: 'sqlite', label: 'SQLite', category: '关系型数据库', defaultPort: 0, defaultDatabase: 'aetheris.db', description: '本地文件型数据库，适合开发与轻量任务' },
  { value: 'h2', label: 'H2', category: '关系型数据库', defaultPort: 0, defaultDatabase: 'mem:aetheris', description: '嵌入式内存数据库，适合本地验收与开发' },
  { value: 'derby', label: 'Apache Derby', category: '关系型数据库', defaultPort: 0, defaultDatabase: 'memory:aetheris', description: 'Apache 嵌入式数据库，适合离线验收' },
  { value: 'clickhouse', label: 'ClickHouse', category: '数仓与湖仓', defaultPort: 8123, defaultDatabase: 'default', description: '列式 OLAP 分析数据库' },
  { value: 'trino', label: 'Trino', category: '企业与联邦查询', defaultPort: 8080, defaultDatabase: 'hive', description: '跨数据源联邦 SQL 查询引擎' },
  { value: 'timescaledb', label: 'TimescaleDB', category: '分布式数据库', defaultPort: 5432, defaultDatabase: 'postgres', description: 'PostgreSQL 兼容时序数据库' }
]

export const dataSourceGroups = (['关系型数据库', '分布式数据库', '数仓与湖仓', '企业与联邦查询'] as const).map((category) => ({
  label: category,
  options: dataSourceCatalog
    .filter((item) => item.category === category)
    .map((item) => ({ value: item.value, label: item.label }))
}))

export const dataSourceOptions = dataSourceCatalog.map((item) => ({
  value: item.value,
  label: item.label
}))

export function getDataSourceDefinition(type?: string) {
  return dataSourceCatalog.find((item) => item.value === type) ?? dataSourceCatalog[0]
}

export function isEmbeddedDataSource(type?: string) {
  return ['sqlite', 'h2', 'derby'].includes(type?.toLowerCase() ?? '')
}

/**
 * 能力口径与后端 JDBC/transfer/CDC 边界保持一致，避免把“可连接”误写成“可写入/可 CDC”。
 */
export function getDataSourceCapabilities(type?: string) {
  const normalized = type?.toLowerCase()
  const query = ['连接探测', '查询', '元数据', '预览']
  if (normalized === 'postgresql' || normalized === 'mysql') {
    return [...query, '批量写入', '首批 CDC']
  }
  if (normalized === 'timescaledb' || normalized === 'mariadb') {
    return [...query, '批量写入']
  }
  if (['greenplum', 'opengauss', 'cockroachdb', 'yugabytedb', 'tidb', 'oceanbase', 'doris', 'starrocks'].includes(normalized || '')) {
    return [...query, '批量写入']
  }
  if (normalized === 'hive2' || normalized === 'spark') {
    return [...query, 'Hive/Spark SQL 写入']
  }
  return query
}

export interface ConnectionPreset {
  key: string
  title: string
  description: string
  values: {
    name: string
    type: string
    host: string
    port: number
    databaseName: string
    username?: string
    connectionParams?: string
    description?: string
  }
}

export const hiveConnectionPresets: ConnectionPreset[] = [
  {
    key: 'wsl-spark',
    title: 'WSL · Spark Thrift',
    description: '先运行 scripts/windows-wsl-hive-relay.ps1，再由 Docker 访问 WSL',
    values: {
      name: 'WSL Hive 测试库',
      type: 'spark',
      host: '172.22.208.1',
      port: 10000,
      databaseName: 'default',
      username: 'aetheris',
      description: 'WSL Spark Thrift Server，通过 Hyper-V 内部临时转发访问 Hive Metastore'
    }
  },
  {
    key: 'vmware-hive',
    title: 'VMware · HiveServer2',
    description: '四节点集群主节点；需先确保 10000 端口已监听',
    values: {
      name: 'VMware Hive 集群',
      type: 'hive2',
      host: '192.168.30.201',
      port: 10000,
      databaseName: 'default',
      username: 'hive',
      connectionParams: 'auth=noSasl',
      description: 'VMware NAT 网段 Hive 集群主节点'
    }
  }
]
