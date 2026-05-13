runs with:

cd ~/Desktop/pyrunner-k8s/pyrunner
eval $(minikube docker-env)
docker build -t pyrunner:latest .
kubectl rollout restart deployment/pyrunner -n pyrunner
kubectl delete pod -n pyrunner --all --force --grace-period=0
kubectl rollout status deployment/pyrunner -n pyrunner
kubectl port-forward svc/pyrunner 9090:80 -n pyrunner
