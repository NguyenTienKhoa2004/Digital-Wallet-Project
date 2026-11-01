variable "aws_region" {
  description = "AWS region for the Terraform state bucket"
  type        = string
  default     = "ap-southeast-1"
}

variable "state_bucket_name" {
  description = "Globally unique name for the Terraform state bucket"
  type        = string
}
