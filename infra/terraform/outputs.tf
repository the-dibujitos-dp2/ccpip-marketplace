output "public_ip" {
  value = aws_eip.app.public_ip
}

output "app_domain" {
  description = "Valor para la variable DEPLOY_HOST del repositorio"
  value       = "${replace(aws_eip.app.public_ip, ".", "-")}.sslip.io"
}

output "app_url" {
  value = "https://${replace(aws_eip.app.public_ip, ".", "-")}.sslip.io"
}

output "ssh_command" {
  value = "ssh -i labsuser.pem ec2-user@${aws_eip.app.public_ip}"
}

output "images_bucket" {
  description = "Valor para la variable IMAGES_BUCKET del repositorio"
  value       = data.aws_s3_bucket.imagenes.bucket
}
