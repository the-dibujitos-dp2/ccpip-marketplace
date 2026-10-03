terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Estado remoto en S3 con lock nativo (sin DynamoDB). El bucket se crea una sola vez,
  # ver docs/devsecops.md. Se pasa con: terraform init -backend-config="bucket=<nombre>"
  backend "s3" {
    key          = "ccpip/prod/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "ccpip-marketplace"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
