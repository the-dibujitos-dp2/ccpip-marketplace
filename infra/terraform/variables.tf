variable "region" {
  description = "Region de AWS Academy Learner Lab"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Nombre del ambiente"
  type        = string
  default     = "prod"
}

variable "instance_type" {
  description = "Tipo de instancia. Learner Lab permite hasta *.large"
  type        = string
  default     = "t3.medium"
}

variable "key_name" {
  description = "Par de llaves de EC2. En Learner Lab ya existe 'vockey' (labsuser.pem)"
  type        = string
  default     = "vockey"
}

variable "instance_profile" {
  description = "Learner Lab no permite crear roles IAM; se usa el perfil que ya trae"
  type        = string
  default     = "LabInstanceProfile"
}

variable "ssh_allowed_cidrs" {
  description = "Origenes permitidos para SSH. GitHub Actions usa IPs variables, por eso 0.0.0.0/0 (solo llave, sin password)"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}
