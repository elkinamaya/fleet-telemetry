output "kafka_bootstrap_servers" {
  description = "Bootstrap address for Spring Boot running on the host."
  value       = "localhost:39092"
}

output "spring_datasource_url" {
  description = "JDBC URL for Spring Boot running on the host."
  value       = "jdbc:postgresql://localhost:55432/fleet"
}

output "postgres_username" {
  description = "Username for the Terraform-managed local database."
  value       = "fleet"
}
