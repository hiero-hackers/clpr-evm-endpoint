{{/*
SPDX-License-Identifier: Apache-2.0
*/}}

{{- define "clpr-evm-endpoint.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "clpr-evm-endpoint.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "clpr-evm-endpoint.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to every resource.
*/}}
{{- define "clpr-evm-endpoint.labels" -}}
helm.sh/chart: {{ include "clpr-evm-endpoint.chart" . }}
{{ include "clpr-evm-endpoint.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels used by Deployment and Service.
*/}}
{{- define "clpr-evm-endpoint.selectorLabels" -}}
app.kubernetes.io/name: {{ include "clpr-evm-endpoint.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Fully-qualified container image reference.
Combines image.registry + image.repository + (image.tag | .Chart.AppVersion).
Leaves image.tag empty in values.yaml so chart upgrades roll the pods to the
image version that matches Chart.appVersion.
*/}}
{{- define "clpr-evm-endpoint.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- if .Values.image.registry -}}
{{- printf "%s/%s:%s" .Values.image.registry .Values.image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}
{{- end }}

{{/*
Name of the ServiceAccount used by the relay pod.
When serviceAccount.name is set it is used as-is; otherwise the fullname is used.
*/}}
{{- define "clpr-evm-endpoint.serviceAccountName" -}}
{{- if .Values.serviceAccount.name }}
{{- .Values.serviceAccount.name }}
{{- else }}
{{- include "clpr-evm-endpoint.fullname" . }}
{{- end }}
{{- end }}

{{/*
Name of the pre-provisioned Secret that holds the signing private key.
Must be set via signing.existingSecret; no chart-managed Secret is created.
*/}}
{{- define "clpr-evm-endpoint.signingSecretName" -}}
{{- required "signing.existingSecret must be set" .Values.signing.existingSecret }}
{{- end }}

{{/*
Namespace
*/}}
{{- define "clpr-evm-endpoint.namespace" -}}
{{- default .Release.Namespace .Values.namespaceOverride -}}
{{- end -}}