#!/usr/bin/env bash
# Skyearch REST API curl 示例
# 用法: 先启动服务(java ... org.eathink.skyearch.Skyearch application.yml), 再执行本脚本
# 注意: Windows 终端下建议用 UTF-8 文件 + --data-binary 传中文 body, 见脚本内 send() 函数

set -u
BASE="${1:-http://127.0.0.1:8080}/api/v1"

echo "== 1. create collection (expect 201)"
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/collections/articles"

echo "== 2. duplicate create (expect 409)"
curl -s -w " %{http_code}\n" -X POST "$BASE/collections/articles"

echo "== 3. create bucket (expect 201)"
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/collections/articles/buckets/default"

echo "== 4. upsert documents (auto-create collection/bucket)"
curl -s -w " %{http_code}\n" -X POST "$BASE/collections/articles/buckets/default/documents/10001" \
     -H "Content-Type: application/json" \
     -d '{"text": "Java Netty 搜索引擎"}'
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/collections/articles/buckets/default/documents/10005" \
     -H "Content-Type: application/json" \
     -d '{"text": "Python 数据分析"}'
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/collections/articles/buckets/default/documents/10008" \
     -H "Content-Type: application/json" \
     -d '{"text": "Netty 网络编程实战"}'

echo "== waiting refresh-interval (default 1000ms) ..."
sleep 1.5

echo "== 5. search single keyword (chinese)"
curl -s -X POST "$BASE/collections/articles/buckets/default/search" \
     -H "Content-Type: application/json" \
     -d '{"query": "搜索引擎"}'
echo

echo "== 6. search AND"
curl -s -X POST "$BASE/collections/articles/buckets/default/search" \
     -H "Content-Type: application/json" \
     -d '{"query": "Netty Java", "operator": "AND"}'
echo

echo "== 7. search OR with paging"
curl -s -X POST "$BASE/collections/articles/buckets/default/search" \
     -H "Content-Type: application/json" \
     -d '{"query": "Netty Python", "operator": "OR", "limit": 2, "offset": 0}'
echo

echo "== 8. get bucket / collection"
curl -s "$BASE/collections/articles/buckets/default"; echo
curl -s "$BASE/collections/articles"; echo

echo "== 9. delete document (204 then 404)"
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE/collections/articles/buckets/default/documents/10005"
sleep 1.5
curl -s -w " %{http_code}\n" -X DELETE "$BASE/collections/articles/buckets/default/documents/10005"

echo "== 10. bad json (expect 400)"
curl -s -w " %{http_code}\n" -X POST "$BASE/collections/articles/buckets/default/documents/1" -d '{bad'

echo "== 11. missing resource (expect 404)"
curl -s -w " %{http_code}\n" "$BASE/collections/missing"

echo "== 12. delete bucket and collection (204)"
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE/collections/articles/buckets/default"
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE/collections/articles"
