#!/usr/bin/env bash
# setup-minikube.sh — run once to bootstrap the full local Kubernetes setup
set -euo pipefail

echo "==> Starting minikube..."
minikube start --memory=4096 --cpus=2

echo "==> Enabling ingress addon..."
minikube addons enable ingress

echo "==> Pulling python sandbox image into minikube..."
minikube image load python:3.11-slim 2>/dev/null || \
  (eval $(minikube docker-env) && docker pull python:3.11-slim)

echo "==> Building pyrunner image inside minikube..."
eval $(minikube docker-env)
docker build -t pyrunner:latest .

echo "==> Applying Kubernetes manifests..."
kubectl apply -f k8s/00-namespaces.yaml
kubectl apply -f k8s/01-rbac.yaml
kubectl apply -f k8s/02-network-policy.yaml
kubectl apply -f k8s/03-deployment.yaml

echo "==> Waiting for pyrunner pod to be ready..."
kubectl rollout status deployment/pyrunner -n pyrunner --timeout=120s

echo ""
echo "✅  Done! To access the app:"
echo ""
echo "   Option A — port-forward (simplest):"
echo "     kubectl port-forward svc/pyrunner 8080:80 -n pyrunner"
echo "     then open http://localhost:8080"
echo ""
echo "   Option B — minikube service:"
echo "     minikube service pyrunner -n pyrunner"
echo ""
echo "   Option C — ingress (apply 04-ingress.yaml first):"
echo "     kubectl apply -f k8s/04-ingress.yaml"
echo "     echo \"\$(minikube ip) pyrunner.local\" | sudo tee -a /etc/hosts"
echo "     open http://pyrunner.local"
