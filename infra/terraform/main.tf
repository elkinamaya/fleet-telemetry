resource "docker_network" "fleet" {
  name   = "fleet-terraform-network"
  driver = "bridge"
}

resource "docker_volume" "kafka_data" {
  name = "fleet-terraform-kafka-data"

  lifecycle {
    prevent_destroy = true
  }
}

resource "docker_volume" "postgres_data" {
  name = "fleet-terraform-postgres-data"

  lifecycle {
    prevent_destroy = true
  }
}

resource "docker_image" "kafka" {
  name         = "apache/kafka:3.9.1"
  keep_locally = true
}

resource "docker_image" "timescaledb" {
  name         = "timescale/timescaledb:2.17.2-pg16"
  keep_locally = true
}

resource "docker_container" "kafka" {
  name  = "fleet-terraform-kafka"
  image = docker_image.kafka.image_id

  env = [
    "KAFKA_NODE_ID=1",
    "KAFKA_PROCESS_ROLES=broker,controller",
    "KAFKA_LISTENERS=INTERNAL://:9092,EXTERNAL://:29092,CONTROLLER://:9093",
    "KAFKA_ADVERTISED_LISTENERS=INTERNAL://fleet-terraform-kafka:9092,EXTERNAL://localhost:39092",
    "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER",
    "KAFKA_CONTROLLER_QUORUM_VOTERS=1@fleet-terraform-kafka:9093",
    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT",
    "KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL",
    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1",
    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1",
    "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1",
    "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0",
    "KAFKA_NUM_PARTITIONS=3",
  ]

  ports {
    internal = 29092
    external = 39092
  }

  mounts {
    type   = "volume"
    source = docker_volume.kafka_data.name
    target = "/var/lib/kafka/data"
  }

  networks_advanced {
    name = docker_network.fleet.name
  }

  healthcheck {
    test         = ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1"]
    interval     = "10s"
    timeout      = "5s"
    retries      = 12
    start_period = "20s"
  }

  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 120
}

resource "docker_container" "timescaledb" {
  name  = "fleet-terraform-timescaledb"
  image = docker_image.timescaledb.image_id

  env = [
    "POSTGRES_DB=fleet",
    "POSTGRES_USER=fleet",
    "POSTGRES_PASSWORD=${var.postgres_password}",
  ]

  ports {
    internal = 5432
    external = 55432
  }

  mounts {
    type   = "volume"
    source = docker_volume.postgres_data.name
    target = "/var/lib/postgresql/data"
  }

  networks_advanced {
    name = docker_network.fleet.name
  }

  healthcheck {
    test         = ["CMD-SHELL", "pg_isready -U fleet -d fleet"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 10
    start_period = "10s"
  }

  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 120
}
