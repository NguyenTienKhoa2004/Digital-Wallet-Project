locals {
  common_tags = merge(var.tags, {
    Project     = "FlowPay"
    Environment = var.environment
    ManagedBy   = "Terraform"
  })
}
