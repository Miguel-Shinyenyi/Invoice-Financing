# Lab sandbox on Kubernetes

Manifests for running the Lab (docs/lab.md) in its own namespace, `settlement-lab`. **They are not applied by CI/CD** and
nothing here is picked up by `kubectl apply -f infra/k8s/` (non-recursive). Where the public sandbox runs is the owner's
decision: running it on the shared staging box would put load there, against the rule in docs/testing.md.

For a single-machine try-out, `docker compose -f infra/docker-compose.lab.yml up --build` is simpler.

## Images

Build and push to your registry with the `lab` tag (the manifests expect `localhost:15000`):

```
docker build -t localhost:15000/settlement-engine:lab .
docker build -t localhost:15000/fraud-detection-ml:lab ml-service
docker build -t localhost:15000/frontend:lab frontend
```

## Secrets and config (created by command, never stored in a file)

```
kubectl apply -f infra/lab/00-namespace.yaml
kubectl -n settlement-lab create secret generic lab-postgres --from-literal=user=lab --from-literal=password="$(openssl rand -hex 16)"
kubectl -n settlement-lab create secret generic lab-jwt --from-literal=secret="$(openssl rand -hex 32)"
kubectl -n settlement-lab create configmap lab-alert-rules --from-file=alert-rules.yml=infra/monitoring/alert-rules.yml
```

`lab-alert-rules` is the one canonical rules file (infra/monitoring/alert-rules.yml). The Prometheus pod shortens only the
`for:` durations; the backend mounts the same ConfigMap read-only so the UI can show the real durations beside them.

## Apply

```
kubectl apply -f infra/lab/
```

Edit the Ingress host in `05-frontend.yaml` first. The backend refuses to start if `LAB_JWT_SECRET` is missing or the
database name does not end in `_lab`.
