variable "postgres_password" {
  description = "Password for the local Terraform-managed PostgreSQL container."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.postgres_password) > 0
    error_message = "postgres_password must not be empty."
  }
}
