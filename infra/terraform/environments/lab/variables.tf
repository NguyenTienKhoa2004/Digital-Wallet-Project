variable "aws_region" {
  description = "AWS region dùng cho hạ tầng FlowPay lab."
  type        = string
  default     = "ap-southeast-1"

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]+$", var.aws_region))
    error_message = "Region phải có định dạng như ap-southeast-1; cần kiểm tra region thực tế trên AWS."
  }
}

variable "environment" {
  description = "Tên môi trường; root configuration này chỉ dành cho lab."
  type        = string
  default     = "lab"

  validation {
    condition     = var.environment == "lab"
    error_message = "Root environments/lab chỉ chấp nhận environment = lab."
  }
}

variable "vpc_cidr" {
  description = "IPv4 CIDR của VPC; kiểm tra không trùng mạng local hoặc VPN trước khi triển khai."
  type        = string
  default     = "10.20.0.0/16"

  validation {
    condition = try(
      can(cidrnetmask(var.vpc_cidr)) &&
      tonumber(split("/", var.vpc_cidr)[1]) >= 16 &&
      tonumber(split("/", var.vpc_cidr)[1]) <= 28 &&
      var.vpc_cidr == "${cidrhost(var.vpc_cidr, 0)}/${split("/", var.vpc_cidr)[1]}",
      false
    )
    error_message = "VPC CIDR phải là IPv4 network hợp lệ, có prefix từ /16 đến /28, ví dụ 10.20.0.0/16."
  }
}

variable "public_subnet_cidr" {
  description = "IPv4 CIDR của public subnet, nằm trong VPC và có prefix dài hơn VPC."
  type        = string
  default     = "10.20.1.0/24"

  validation {
    condition = try(
      can(cidrnetmask(var.public_subnet_cidr)) &&
      tonumber(split("/", var.public_subnet_cidr)[1]) > tonumber(split("/", var.vpc_cidr)[1]) &&
      tonumber(split("/", var.public_subnet_cidr)[1]) <= 28 &&
      var.public_subnet_cidr == "${cidrhost(var.public_subnet_cidr, 0)}/${split("/", var.public_subnet_cidr)[1]}" &&
      cidrhost("${cidrhost(var.public_subnet_cidr, 0)}/${split("/", var.vpc_cidr)[1]}", 0) == cidrhost(var.vpc_cidr, 0),
      false
    )
    error_message = "Subnet phải là IPv4 network nằm trong VPC, nhỏ hơn VPC và có prefix tối đa /28."
  }
}

variable "instance_type" {
  description = "Loại EC2 ARM64 cho lab; giới hạn lựa chọn ban đầu để kiểm soát cấu hình và chi phí."
  type        = string
  default     = "t4g.small"

  validation {
    condition     = contains(["t4g.small", "t4g.medium"], var.instance_type)
    error_message = "Lab chỉ cho phép t4g.small hoặc t4g.medium; cập nhật estimate trước khi đổi cấu hình."
  }
}

variable "tags" {
  description = "Tags bổ sung, bắt buộc có Owner; Project, Environment và ManagedBy được chốt trong locals.tf."
  type        = map(string)
  default = {
    Owner = "johnny"
  }

  validation {
    condition = try(
      length(trimspace(var.tags["Owner"])) > 0 &&
      alltrue([for key, value in var.tags : length(trimspace(key)) > 0 && length(trimspace(value)) > 0]) &&
      alltrue([for key in keys(var.tags) : !contains(["Project", "Environment", "ManagedBy"], key)]),
      false
    )
    error_message = "Tags phải có Owner không rỗng, các key/value không rỗng; không truyền Project, Environment hoặc ManagedBy."
  }
}
