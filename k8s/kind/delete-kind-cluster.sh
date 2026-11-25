#!/usr/bin/env bash
echo "Deleting Kind Cluster microservices"
kind delete cluster --name microservices
echo "Kind Cluster microservices Deleted"
