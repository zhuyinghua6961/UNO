FROM node:24-alpine AS build
WORKDIR /workspace/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
COPY assets/ready/ ./public/game-assets/
COPY assets/vendor/4colour-cards/LICENSE-SOURCE.md ./public/game-assets/licenses/cards.md
COPY assets/vendor/casino-audio/License.txt ./public/game-assets/licenses/audio.txt
COPY assets/vendor/ui-pack/License.txt ./public/game-assets/licenses/ui.txt
COPY assets/vendor/board-game-icons/License.txt ./public/game-assets/licenses/icons.txt
RUN npm run build

FROM nginx:1.28-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/web/dist /usr/share/nginx/html
EXPOSE 80
