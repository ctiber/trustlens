FROM node:22-alpine
WORKDIR /app
# node_modules is installed on the host (npm install --omit=dev) and copied,
# so that the image builds without network access.
COPY package.json index.js ./
COPY node_modules ./node_modules
EXPOSE 9000
CMD ["node", "index.js"]
