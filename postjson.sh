#!/bin/bash -eux
#Convenience script for sending queries to a test server
curl -i -H "Content-Type: application/json" -X POST -d "$2" "http://localhost:8080/$1"
{ set +x; } 2>/dev/null
echo ""
