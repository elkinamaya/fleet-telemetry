# Terraform: entorno Docker local alternativo

Este directorio declara una alternativa local independiente para Kafka y
TimescaleDB. Docker Compose sigue siendo el mecanismo principal del proyecto.
No arranques ambos entornos a la vez: aunque usan puertos, redes, nombres y
volúmenes distintos, ambos consumen recursos locales y contienen datos
independientes.

La configuración usa el proveedor `kreuzwerker/docker` fijado en `3.6.2`,
Kafka `apache/kafka:3.9.1` en modo KRaft de un solo nodo y
`timescale/timescaledb:2.17.2-pg16`. No crea recursos cloud ni utiliza
`local-exec`.

## Prerrequisitos

- Terraform CLI `1.5.0` o posterior.
- Docker Desktop / Docker Engine con el daemon disponible y un contexto válido
  para cuando se decida crear el entorno.
- Conectividad al Terraform Registry para `terraform init`.
- Una contraseña local para PostgreSQL. No se define una contraseña por
  defecto.

## Validación estática

Desde la raíz del repositorio, en PowerShell:

```powershell
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform validate
```

`init` descarga el proveedor y genera `.terraform.lock.hcl`; revisa y conserva
ese lockfile en Git para fijar la selección y los checksums del proveedor. Estos
comandos no crean contenedores ni necesitan conectarse a un daemon Docker.
`init` sí requiere acceso de red para descargar el proveedor.

## Contraseña y estado

En PowerShell, define la contraseña solo en la sesión actual antes de cualquier
operación Terraform que necesite evaluar la variable:

```powershell
$env:TF_VAR_postgres_password = Read-Host "Contraseña local de PostgreSQL"
```

No escribas la contraseña en archivos versionados ni en la línea de comandos.
La variable Terraform está marcada como sensible para redactar su presentación,
pero **el estado local de Terraform puede contener la contraseña y otros datos
sensibles**. El estado, los archivos `.tfvars` y el caché local del proveedor
están excluidos de Git. Protege el acceso al directorio de trabajo y no publiques
ni compartas el estado.

## Recursos y conexión de Spring Boot

Terraform declara una red bridge y volúmenes propios (`fleet-terraform-*`),
además de los dos contenedores. No reutiliza ni importa recursos de Compose.
Los puertos publicados son:

| Servicio | Host | Contenedor |
| --- | ---: | ---: |
| Kafka external listener | `39092` | `29092` |
| PostgreSQL/TimescaleDB | `55432` | `5432` |

El listener interno de Kafka usa
`fleet-terraform-kafka:9092`; el listener del controlador KRaft usa
`fleet-terraform-kafka:9093`. Ninguno de esos puertos se publica al host. El
listener externo anuncia `localhost:39092`, que es la dirección adecuada para
Spring Boot ejecutado en el host: el cliente se conecta a ese puerto y recibe
metadata con una dirección alcanzable desde el host.

Cuando se use este entorno, configura Spring Boot en el host con:

```powershell
$env:KAFKA_BOOTSTRAP_SERVERS = "localhost:39092"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:55432/fleet"
$env:POSTGRES_USER = "fleet"
$env:POSTGRES_PASSWORD = $env:TF_VAR_postgres_password
```

El proveedor declara checks de salud para que Terraform pueda esperar a que
Kafka y PostgreSQL estén listos. La prueba efectiva de conectividad debe hacerse
con Docker activo y los contenedores iniciados; la validación estática no
demuestra conectividad en ejecución.

## Persistencia y destrucción

Los volúmenes de Kafka y PostgreSQL tienen `prevent_destroy = true`. Mientras
esa protección esté en la configuración, Terraform rechazará un plan que intente
destruirlos, incluido un `terraform destroy` del entorno. Los contenedores y la
red no tienen esa protección. No se debe retirar la protección para una
destrucción rutinaria.

La protección no es un backup y no impide borrar volúmenes manualmente desde
Docker. Antes de cualquier eliminación deliberada, crea y verifica los
respaldos requeridos; después de una revisión explícita, un operador tendría
que retirar la protección y evaluar el plan resultante. `docker_image` usa
`keep_locally = true`, así que destruir el recurso Terraform no borra las
imágenes descargadas.

Este stack local no ofrece alta disponibilidad ni backups automáticos. El
usuario es responsable de los datos y del consumo de disco, memoria y CPU.
