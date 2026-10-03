# CCPIP-23: ambiente desplegado en una EC2 con Docker Compose (Caddy + Spring Boot + PostgreSQL).

data "aws_vpc" "default" {
  default = true
}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
}

resource "aws_security_group" "app" {
  name        = "ccpip-${var.environment}-app"
  description = "CCPIP: HTTP/HTTPS publico y SSH para el despliegue"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP (Caddy redirige a HTTPS y resuelve el reto ACME)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    # sitio publico
    # trivy:ignore:AVD-AWS-0107
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    # sitio publico
    # trivy:ignore:AVD-AWS-0107
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP/3"
    from_port   = 443
    to_port     = 443
    protocol    = "udp"
    # sitio publico
    # trivy:ignore:AVD-AWS-0107
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "SSH para el despliegue desde GitHub Actions"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    # runners de GitHub sin IP fija
    # trivy:ignore:AVD-AWS-0107
    cidr_blocks = var.ssh_allowed_cidrs
  }

  egress {
    description = "Salida para descargar imagenes y certificados"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    # necesita GHCR, Docker Hub y Let's Encrypt
    # trivy:ignore:AVD-AWS-0104
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "app" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  iam_instance_profile   = var.instance_profile
  vpc_security_group_ids = [aws_security_group.app.id]
  user_data              = file("${path.module}/user_data.sh")

  # IMDSv2 obligatorio
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  tags = {
    Name = "ccpip-${var.environment}"
  }

  lifecycle {
    # Una AMI nueva no debe recrear el servidor (y borrar la base) en cada apply
    ignore_changes = [ami, user_data]
  }
}

# IP fija: Learner Lab apaga la instancia al cerrar la sesion y sin EIP la IP cambiaria
resource "aws_eip" "app" {
  instance = aws_instance.app.id
  domain   = "vpc"

  tags = {
    Name = "ccpip-${var.environment}"
  }
}
