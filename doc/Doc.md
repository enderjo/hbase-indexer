# 说明
http://192.168.192.92:3000/jkzl.cloud/hbase-indexer.git分支hbase-indexer-1.6
## 主要修改
1. 支持allow Empty，增加utc date格式。
2. 修改readrow逻辑，WAL数据与Hbase数据合并。
3. 修改ZK版本号。

当前测试情况，厦门开发环境能正常拉取数据。但有出现无法连接ZK，异常退出情况（之前华三版本也有此问题）。需要部署正式环境验证。

另因为我们没有华三的原始代码，还不知道他们有改了哪些内容，只能验证数据是否符合要求及是否出错。

# 编译
```bash
mvn clean package -Pdist -DskipTests
```
## 发布包
`hbase-indexer-dist/target/hbase-indexer-1.6-nhdata.tar.gz`
## 发布目录
`hbase-indexer-dist/target/hbase-indexer-1.6-nhdata/hbase-indexer-1.6-nhdata`

# 配置
此处的配置为替换原有华三的HBase所需的操作。
## hbase路径
`/usr/hdp/2.3.4.0-3485/hbase`
## hbase-indexer路径
`/opt/hbase-indexer/latest`
## 备份

## 替换
### hbase-indexer
`hbase-indexer-dist/target/hbase-indexer-1.6-nhdata/hbase-indexer-1.6-nhdata`
下除`bin`和`conf`的所有文件覆盖`/opt/hbase-indexer/latest`

### hbase lib
将`hbase-indexer-dist/target/hbase-indexer-1.6-nhdata/hbase-indexer-1.6-nhdata/lib`的`hbase-sep-*`
拷贝到所有Hbase RegionServer的`lib`目录。
```bash
cp lib/hbase-sep-* $HBASE_HOME/lib
```

# DE操作
- 重启Hbase
- 重启Hbase-indexer

