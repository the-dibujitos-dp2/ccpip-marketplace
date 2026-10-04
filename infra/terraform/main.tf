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

  # IMDSv2 obligatorio. Limite de saltos 2: los contenedores Docker suman un salto de red y
  # con 1 el backend no podria leer las credenciales del perfil de la instancia para S3.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
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

# ---------------------------------------------------------------------------------------
# Imagenes de tiendas y productos (CCPIP-30, CCPIP-44). Bucket privado: las imagenes se
# sirven por el backend (/api/imagenes), nunca con la URL del bucket. El backend accede con
# el perfil LabInstanceProfile de la EC2, sin llaves.
# ---------------------------------------------------------------------------------------

data "aws_caller_identity" "actual" {}

resource "aws_s3_bucket" "imagenes" {
  bucket = "ccpip-imagenes-${data.aws_caller_identity.actual.account_id}"

  tags = {
    Name = "ccpip-imagenes-${var.environment}"
  }
}

resource "aws_s3_bucket_public_access_block" "imagenes" {
  bucket                  = aws_s3_bucket.imagenes.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "imagenes" {
  bucket = aws_s3_bucket.imagenes.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Cifrado con la llave administrada por S3. Una llave KMS propia no aporta para fotos de
# catalogo publico y AWS Academy limita la gestion de llaves.
# trivy:ignore:AVD-AWS-0132
resource "aws_s3_bucket_server_side_encryption_configuration" "imagenes" {
  bucket = aws_s3_bucket.imagenes.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Las versiones viejas (fotos reemplazadas o borradas) se limpian a los 30 dias
resource "aws_s3_bucket_lifecycle_configuration" "imagenes" {
  bucket = aws_s3_bucket.imagenes.id
  rule {
    id     = "limpiar-versiones-viejas"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}
