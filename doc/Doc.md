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

